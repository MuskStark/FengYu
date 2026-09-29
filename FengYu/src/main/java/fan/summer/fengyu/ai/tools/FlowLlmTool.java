package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fan.summer.fengyu.ai.AiConfigService;
import fan.summer.fengyu.ai.FengYuTool;
import fan.summer.fengyu.ai.config.ChatModelConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.StructuredOutputConverter;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * LLM-call node of the canvas ({@code flow_llm}): one non-interactive model completion
 * as an ordinary flow step — prompt assembly from upstream references, optional system
 * role, optional temperature, and an optional JSON Schema for structured output.
 *
 * <p>Design notes, distilled from the surveyed builders (n8n LLM Chain, Dify LLM node,
 * Flowise structured output):
 * <ul>
 *   <li><b>The raw text always survives.</b> Dify keeps {@code text} even with structured
 *       output enabled and n8n's output parser is notoriously unreliable — so this node
 *       returns {@code text} unconditionally and {@code data} only when a schema was
 *       requested and parsed. A failed parse never discards the model's answer.</li>
 *   <li><b>Structured output is Spring AI's, end to end.</b> The "answer ONLY JSON"
 *       instruction comes from a {@link DynamicSchemaConverter} (the documented custom
 *       {@link StructuredOutputConverter} extension, mirroring
 *       {@code BeanOutputConverter.getFormat()}); validation against the caller's schema
 *       and the single targeted repair — the validation error fed back into the prompt —
 *       are {@link StructuredOutputValidationAdvisor} (real draft-2020-12 schema
 *       validation, {@code maxRepeatAttempts(1)} to keep this node's one-repair
 *       contract). When every attempt fails validation the advisor returns the last
 *       response as-is, which is exactly the raw-text-survives rule above.</li>
 *   <li><b>A fresh model per call.</b> The active {@code ChatBackend} admits a single
 *       concurrent generation (its {@code generating} CAS), which a flow executed from
 *       chat via {@code run_current_flow} would deadlock against. Building a one-shot
 *       {@link ChatModel} from the live config instead shares no state — parallel
 *       canvas steps and chat-embedded flows both work.</li>
 * </ul>
 */
@Component
public class FlowLlmTool implements FengYuTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Matches the planner's patience; a flow step must terminate even on a hung model. */
    private static final long TIMEOUT_SECONDS = 180;

    /**
     * One LLM completion for the flow canvas.
     *
     * @param prompt         the user prompt; canvas references are resolved by the engine first
     * @param system         optional system role ("你是邮件文案助手")
     * @param temperature    optional sampling temperature 0–2; null uses the global AI setting
     * @param responseSchema optional JSON Schema (as JSON text); when set the model is
     *                       instructed to answer with a matching JSON object, parsed into
     *                       {@code data} with one error-feedback retry
     * @return {@code {"success":bool,"summary":…,"error":…?,"text":raw,"data":object?}}
     */
    @Tool(name = "flow_llm",
          description = "Run one LLM completion with a prompt (optionally a system role, "
                  + "temperature, and a JSON Schema for structured output). "
                  + "Returns {\"success\",\"summary\",\"text\",\"data\"}.")
    public String flowLlm(String prompt,
                          @ToolParam(required = false,
                                     description = "Optional system role for the model.") String system,
                          @ToolParam(required = false,
                                     description = "Optional sampling temperature 0-2; omit for the global setting.")
                          Double temperature,
                          @ToolParam(required = false,
                                     description = "Optional JSON Schema object; when given, the reply is a matching JSON object in `data`.")
                          String responseSchema) {
        if (prompt == null || prompt.isBlank()) {
            return failure("prompt is required");
        }
        if (temperature != null && (temperature < 0 || temperature > 2)) {
            return failure("temperature must be between 0 and 2");
        }
        JsonNode schema = null;
        if (responseSchema != null && !responseSchema.isBlank()) {
            try {
                schema = MAPPER.readTree(responseSchema);
            } catch (Exception e) {
                return failure("responseSchema is not valid JSON: " + e.getMessage());
            }
        }

        if (schema == null) {
            String raw;
            try {
                raw = complete(system, prompt, temperature);
            } catch (Exception e) {
                return failure(messageOf(e));
            }
            return success(raw, null);
        }

        // Structured path: Spring AI converter provides the format instruction; the
        // validation advisor validates the reply against the caller's schema and makes
        // ONE targeted repair with the error fed back into the prompt. Whatever text
        // comes back last is the raw answer — parsed leniently into `data`.
        DynamicSchemaConverter converter = new DynamicSchemaConverter(schema.toString());
        String instruction = prompt + "\n\n" + converter.getFormat();
        String raw;
        try {
            raw = complete(system, instruction, temperature, StructuredOutputValidationAdvisor.builder()
                    .outputJsonSchema(schema.toString())
                    .maxRepeatAttempts(1)
                    .build());
        } catch (Exception e) {
            return failure(messageOf(e));
        }
        JsonNode data = null;
        try {
            data = converter.convert(raw);
        } catch (Exception ignored) {
            // Validation exhausted its repair and the last reply still does not parse —
            // the raw answer must survive even when structuring it did not.
        }
        return success(raw, data);
    }

    private static String messageOf(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * One blocking completion against a FRESH model built from the live AI config, via
     * Spring AI's {@link ChatClient}. The optional validation advisor (structured path)
     * wraps the call with schema validation + one targeted repair. Protected so unit
     * tests can stub the model call and still exercise the output contract.
     */
    protected String complete(String system, String userPrompt, Double temperature,
            StructuredOutputValidationAdvisor... validation) throws Exception {
        ChatModelConfig.ResolvedModel resolved = resolveModel();
        var baseOptions = resolved.options();
        return boundedModelCall(TIMEOUT_SECONDS, () -> {
            ChatClient.ChatClientRequestSpec spec = ChatClient.create(resolved.chatModel()).prompt();
            if (system != null && !system.isBlank()) spec = spec.system(system);
            if (baseOptions != null) {
                // ChatClient's .options() takes an options BUILDER derived from the
                // provider-specific base (mutate() preserves the concrete type).
                var builder = baseOptions.mutate();
                if (temperature != null) builder = builder.temperature(temperature);
                spec = spec.options(builder);
            }
            if (validation.length > 0) {
                spec = spec.advisors(a -> a.advisors(validation[0]));
            }
            String text = spec.user(userPrompt).call().content();
            return text == null ? "" : text;
        });
    }

    /**
     * Runs one model call on its own virtual thread with a hard wall clock. On timeout the
     * executor is abandoned (interrupt + no close-join): {@code ExecutorService.close()} waits
     * for task termination, so joining a hung HTTP call would defeat the timeout and hold the
     * flow step hostage far past {@code timeoutSeconds}.
     */
    static String boundedModelCall(long timeoutSeconds,
            java.util.concurrent.Callable<String> call) throws Exception {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<String> future = executor.submit(call);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            executor.shutdownNow();
            throw new IllegalStateException("LLM call timed out after " + timeoutSeconds + "s");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException(cause.getMessage() == null
                    ? cause.getClass().getSimpleName() : cause.getMessage());
        } finally {
            if (!executor.isShutdown()) executor.close();
        }
    }

    /** Resolves the CURRENT mode into a one-shot model — never the shared, CAS-guarded backend. */
    protected ChatModelConfig.ResolvedModel resolveModel() {
        String mode = AiConfigService.getAiMode();
        return switch (mode) {
            case "openai" -> ChatModelConfig.buildOpenAiCompatible(
                    AiConfigService.getAiOpenAiEndpoint(),
                    AiConfigService.getAiOpenAiApiKey(),
                    AiConfigService.getAiOpenAiModel());
            case "deepseek" -> ChatModelConfig.buildOpenAiCompatible(
                    AiConfigService.getAiDeepSeekEndpoint(),
                    AiConfigService.getAiDeepSeekApiKey(),
                    AiConfigService.getAiDeepSeekModel());
            case "anthropic" -> ChatModelConfig.buildAnthropic(
                    AiConfigService.getAiAnthropicEndpoint(),
                    AiConfigService.getAiAnthropicApiKey(),
                    AiConfigService.getAiAnthropicModel());
            // "local" rides the Ollama backend; since Spring AI 2.0 the options must ride
            // along too (OllamaChatModel rejects tool-carrying options without a model),
            // so local now gets the same mutate()-based option path as the cloud providers.
            case "local" -> ChatModelConfig.buildOllama(
                    AiConfigService.getAiOllamaBaseUrl(),
                    AiConfigService.getAiOllamaModel());
            default -> throw new IllegalStateException(
                    "Unknown AI mode '" + mode + "' — check the AI settings");
        };
    }

    // ── structured-output converter (Spring AI extension point) ────────

    /**
     * {@link StructuredOutputConverter} for a CALLER-SUPPLIED schema: the format
     * instruction mirrors {@code BeanOutputConverter.getFormat()} verbatim with the
     * dynamic schema in place of the generated one; {@link #getJsonSchema()} exposes
     * the schema so Spring AI's schema-driven features (validation, native structured
     * output) apply to dynamic schemas exactly as they do to generated ones; and
     * {@link #convert(String)} is the documented lenient variant — it strips markdown
     * fences and picks the first balanced JSON object before parsing.
     */
    static final class DynamicSchemaConverter implements StructuredOutputConverter<JsonNode> {

        private final String schemaText;

        DynamicSchemaConverter(String schemaText) {
            this.schemaText = schemaText;
        }

        @Override
        public String getFormat() {
            return String.format("""
                    Your response should be in JSON format.
                    Do not include any explanations, only provide a RFC8259 compliant JSON response following this format without deviation.
                    Do not include markdown code blocks in your response.
                    Remove the ```json markdown from the output.
                    Here is the JSON Schema instance your output must adhere to:
                    ```%s```
                    """, schemaText);
        }

        @Override
        public String getJsonSchema() {
            return schemaText;
        }

        @Override
        public JsonNode convert(String source) {
            JsonNode object = extractJsonObject(source);
            if (object == null) {
                throw new IllegalArgumentException("the reply is not a JSON object");
            }
            return object;
        }
    }

    /** Pulls the first balanced top-level JSON object out of a possibly fenced/prose reply. */
    private static JsonNode extractJsonObject(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        // Strip a ```/```json code fence when present.
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int closing = text.lastIndexOf("```");
            if (firstNewline >= 0 && closing > firstNewline) {
                text = text.substring(firstNewline + 1, closing).trim();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JsonNode parsed = MAPPER.readTree(text.substring(start, end + 1));
            return parsed.isObject() ? parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ── result shapes ───────────────────────────────────────────────────

    private static String success(String raw, JsonNode data) {
        ObjectNode output = successNode(raw);
        if (data != null) output.set("data", data);
        else output.set("data", null);
        return write(output);
    }

    private static ObjectNode successNode(String raw) {
        ObjectNode output = MAPPER.createObjectNode();
        output.put("success", true);
        String single = raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
        output.put("summary", single.length() > 140 ? single.substring(0, 139) + "…" : single);
        output.put("text", raw == null ? "" : raw);
        return output;
    }

    private static String failure(String message) {
        ObjectNode output = MAPPER.createObjectNode();
        output.put("success", false);
        output.put("error", message);
        return write(output);
    }

    private static String write(ObjectNode output) {
        try {
            return MAPPER.writeValueAsString(output);
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"result serialization failed\"}";
        }
    }
}
