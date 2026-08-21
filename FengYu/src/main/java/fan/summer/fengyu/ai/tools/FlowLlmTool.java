package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fan.summer.fengyu.ai.AiConfigService;
import fan.summer.fengyu.ai.FengYuTool;
import fan.summer.fengyu.ai.config.ChatModelConfig;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.model.ChatModel;
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
 *   <li><b>Targeted repair over blind re-roll.</b> When a schema is requested and the
 *       reply fails to parse/validate, ONE retry feeds the exact error back into the
 *       prompt — measurably better than re-rolling at the same temperature.</li>
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

        String instruction = prompt;
        if (schema != null) {
            instruction = prompt + "\n\nRespond with ONLY one JSON object — no prose, no code fences — "
                    + "conforming to this JSON Schema:\n" + schema;
        }

        String raw;
        try {
            raw = complete(system, instruction, temperature);
        } catch (Exception e) {
            return failure(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }

        if (schema == null) {
            return success(raw, null);
        }

        // Structured path: parse + validate, then ONE targeted repair with the error fed back.
        String problem = structuredProblem(raw, schema);
        if (problem == null) {
            return success(raw, extractJsonObject(raw));
        }
        String repairPrompt = instruction
                + "\n\nYour previous reply was rejected: " + problem
                + "\nRespond again with ONLY the corrected JSON object.";
        try {
            String repaired = complete(system, repairPrompt, temperature);
            if (structuredProblem(repaired, schema) == null) {
                return success(repaired, extractJsonObject(repaired));
            }
        } catch (Exception ignored) {
            // The repair attempt failed; fall through with the ORIGINAL text — the raw
            // answer must survive even when structuring it did not.
        }
        ObjectNode output = successNode(raw);
        output.set("data", null);
        return write(output);
    }

    /**
     * One blocking completion against a FRESH model built from the live AI config.
     * Protected so unit tests can stub the model call and still exercise the
     * schema/parse/retry contract.
     */
    protected String complete(String system, String userPrompt, Double temperature) throws Exception {
        ChatModelConfig.ResolvedModel resolved = resolveModel();
        List<Message> messages = new ArrayList<>();
        if (system != null && !system.isBlank()) messages.add(new SystemMessage(system));
        messages.add(new UserMessage(userPrompt));
        var options = resolved.options();
        if (options != null && temperature != null) {
            options = options.mutate().temperature(temperature).build();
        }
        Prompt springPrompt = options != null
                ? new Prompt(messages, options)
                : new Prompt(messages);
        return boundedModelCall(TIMEOUT_SECONDS, () -> {
            var response = resolved.chatModel().call(springPrompt);
            var output = response.getResult().getOutput();
            return output.getText() == null ? String.valueOf(output) : output.getText();
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
    private ChatModelConfig.ResolvedModel resolveModel() {
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
            // "local" rides the Ollama backend; that builder returns a bare ChatModel
            // with its options baked in, so no separate options ride along.
            case "local" -> new ChatModelConfig.ResolvedModel(
                    ChatModelConfig.buildOllama(
                            AiConfigService.getAiOllamaBaseUrl(),
                            AiConfigService.getAiOllamaModel()),
                    null);
            default -> throw new IllegalStateException(
                    "Unknown AI mode '" + mode + "' — check the AI settings");
        };
    }

    // ── structured-output helpers ───────────────────────────────────────

    /** Null when the reply parses as an object satisfying the schema's required keys. */
    private static String structuredProblem(String raw, JsonNode schema) {
        JsonNode object = extractJsonObject(raw);
        if (object == null || !object.isObject()) {
            return "the reply is not a JSON object";
        }
        JsonNode required = schema.path("required");
        if (required.isArray()) {
            for (JsonNode key : required) {
                if (!object.has(key.asText())) {
                    return "missing required field '" + key.asText() + "'";
                }
            }
        }
        return null;
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
