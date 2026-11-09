package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.AiConfigService;
import fan.summer.fengyu.ai.ChatBackend;
import fan.summer.fengyu.ai.mcp.McpRuntimeManager;
import io.modelcontextprotocol.spec.McpSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Bridges MCP Apps sampling callbacks to the ACTIVE chat backend. An MCP server (or a sandbox
 * page through the UI controller) may ask the host's model to complete a prompt; this wiring
 * resolves whichever backend {@link AiModeService} currently serves — cloud or local Ollama —
 * at REQUEST time, so mode switches take effect for the next sample without a reconnect.
 *
 * <p>Budgets are hard caps, not preferences: at most {@link #maxSamplingMessages()} messages
 * with {@link #maxMessageChars()} each, completing at most {@link #maxSamplingTokens()} new
 * tokens. The caps default to the conservative MCP-Apps page profile but are operator-tunable
 * without a restart (property then env, mirroring the {@code fengyu.mcp.ui.sampling} gate) —
 * an agent-grade server (a multi-expert pipeline sampling long JSON verdicts) raises
 * {@code fengyu.mcp.sampling.max-tokens} instead of forking the whole feature. The whole
 * feature stays dark until the operator opts in via {@code fengyu.mcp.ui.sampling}
 * (see {@code McpRuntimeManager#samplingEnabled}).
 *
 * <p>Request shaping, beyond the original text-only profile:
 * <ul>
 *   <li>{@code modelPreferences.hints} route the call — in local (Ollama) mode the first hint
 *       passes through (the runtime serves whatever it has pulled), in cloud modes a hint is
 *       honored only when it names the configured model id; otherwise the active backend's
 *       own model serves and the result reports what actually ran.</li>
 *   <li>{@code ImageContent} blocks ride along as Spring AI {@link Media} on user messages
 *       (vision OCR from a sampling server), dropped with a debug log when the active model
 *       is catalog-known text-only or the payload fails to decode.</li>
 *   <li>{@code stopSequences} forward when present.</li>
 * </ul>
 */
@Configuration
public class McpSamplingConfig {

    private static final Logger log = LoggerFactory.getLogger(McpSamplingConfig.class);

    static final int DEFAULT_MAX_SAMPLING_MESSAGES = 40;
    static final int DEFAULT_MAX_MESSAGE_CHARS = 64 * 1024;
    static final int DEFAULT_MAX_SAMPLING_TOKENS = 2048;
    static final int DEFAULT_SAMPLING_TOKENS = 512;

    static final String MAX_TOKENS_PROPERTY = "fengyu.mcp.sampling.max-tokens";
    static final String MAX_TOKENS_ENV = "FENGYU_MCP_SAMPLING_MAX_TOKENS";
    static final String MAX_MESSAGES_PROPERTY = "fengyu.mcp.sampling.max-messages";
    static final String MAX_MESSAGES_ENV = "FENGYU_MCP_SAMPLING_MAX_MESSAGES";
    static final String MAX_MESSAGE_CHARS_PROPERTY = "fengyu.mcp.sampling.max-message-chars";
    static final String MAX_MESSAGE_CHARS_ENV = "FENGYU_MCP_SAMPLING_MAX_MESSAGE_CHARS";

    static final int MAX_LOOP_TOOLS = 16;
    static final int MAX_TOOL_SCHEMA_CHARS = 8 * 1024;
    static final Pattern TOOL_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final ObjectMapper JSON = new ObjectMapper();

    // ── page sampling tool loop (`sampling.tools`, SEP-1865) ───────────────────

    /** One content block of a page loop message: text | tool_use | tool_result. */
    public record UiLoopBlock(String kind, String text, String id, String name,
            Map<String, Object> input, String toolUseId, Boolean isError) {}

    /** Loop message: {user, assistant} role plus ordered blocks. */
    public record UiLoopMessage(String role, List<UiLoopBlock> blocks) {}

    /** Tool definition the page declared for its in-app agent loop. */
    public record UiLoopTool(String name, String description, Map<String, Object> inputSchema) {}

    /** A page loop sampling request (the UI controller forwards it verbatim). */
    public record UiLoopRequest(List<UiLoopMessage> messages, String systemPrompt,
            Integer maxTokens, Double temperature, List<UiLoopTool> tools, String toolChoice) {}

    /** A tool call the model wants the PAGE to execute before it continues. */
    public record UiLoopToolCall(String id, String name, Map<String, Object> input) {}

    /** Loop reply: final text, or the tool calls when stopReason is "toolUse". */
    public record UiLoopResult(String text, String model, String stopReason,
            List<UiLoopToolCall> toolCalls) {}

    @Bean
    public McpSamplingBinding mcpSamplingBinding(McpRuntimeManager runtime, AiModeService modes) {
        runtime.setSamplingHandler(request -> {
            ToolLoopDriver.Transport transport = activeTransport(modes);
            return sample(transport.chatModel(), request, transport.modelLabel(),
                    transport.baseOptions());
        });
        runtime.setUiLoopSampler(request -> {
            ToolLoopDriver.Transport transport = activeTransport(modes);
            return sampleToolLoop(transport.chatModel(), request, transport.modelLabel(),
                    transport.baseOptions(), AiConfigService.getAiMode(),
                    AiConfigService.activeModelId());
        });
        return new McpSamplingBinding();
    }

    /** Shared gate + active-backend resolution for both sampling entry points. */
    private static ToolLoopDriver.Transport activeTransport(AiModeService modes) {
        if (!McpRuntimeManager.samplingEnabled()) {
            throw new McpRuntimeManager.McpRuntimeException("Sampling is disabled");
        }
        ChatBackend backend = modes.getService()
                .orElseThrow(() -> new McpRuntimeManager.McpRuntimeException("No active AI backend"));
        // chatModel()/modelLabel() live on the driver Transport surface both backends
        // implement alongside ChatBackend; a backend that is not turn-capable cannot sample.
        if (!(backend instanceof ToolLoopDriver.Transport transport)
                || transport.chatModel() == null) {
            throw new McpRuntimeManager.McpRuntimeException("The active AI backend has no model");
        }
        return transport;
    }

    /** Marker bean so the wiring is visible in diagnostics; carries no behavior. */
    public static final class McpSamplingBinding {}

    static McpSchema.CreateMessageResult sample(ChatModel model, McpSchema.CreateMessageRequest request,
                                                String modelLabel) {
        return sample(model, request, modelLabel, null);
    }

    static McpSchema.CreateMessageResult sample(ChatModel model, McpSchema.CreateMessageRequest request,
                                                String modelLabel, ToolCallingChatOptions baseOptions) {
        return sample(model, request, modelLabel, baseOptions, AiConfigService.getAiMode(),
                AiConfigService.activeModelId(), AiConfigService.activeModelSupportsImages());
    }

    /** Test/legacy entry: no base options (generic builder fallback path). */
    static McpSchema.CreateMessageResult sample(ChatModel model, McpSchema.CreateMessageRequest request,
                                                String modelLabel, String aiMode, String activeModelId,
                                                boolean imageSupport) {
        return sample(model, request, modelLabel, null, aiMode, activeModelId, imageSupport);
    }

    static McpSchema.CreateMessageResult sample(ChatModel model, McpSchema.CreateMessageRequest request,
                                                String modelLabel, ToolCallingChatOptions baseOptions,
                                                String aiMode, String activeModelId,
                                                boolean imageSupport) {
        List<McpSchema.SamplingMessage> samplingMessages = request.messages() == null
                ? List.of() : request.messages();
        if (samplingMessages.size() > maxSamplingMessages()) {
            throw new McpRuntimeManager.McpRuntimeException(
                    "Sampling request exceeds the " + maxSamplingMessages() + " message cap");
        }
        List<Message> messages = new ArrayList<>();
        String systemPrompt = request.systemPrompt();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new SystemMessage(clamp(systemPrompt)));
        }
        for (McpSchema.SamplingMessage samplingMessage : samplingMessages) {
            McpSchema.Content content = samplingMessage.content();
            if (content instanceof McpSchema.TextContent textContent && textContent.text() != null) {
                // The MCP sampling role set is {user, assistant}; system context rides the
                // dedicated systemPrompt field above.
                messages.add(samplingMessage.role() == McpSchema.Role.ASSISTANT
                        ? (Message) new AssistantMessage(clamp(textContent.text()))
                        : (Message) new UserMessage(clamp(textContent.text())));
            } else if (content instanceof McpSchema.ImageContent image
                    && samplingMessage.role() == McpSchema.Role.USER && imageSupport) {
                Message media = mediaMessage(image);
                if (media != null) messages.add(media);
            } else {
                log.debug("Dropping unservable sampling content block: role={} type={}",
                        samplingMessage.role(), content == null ? "null" : content.type());
            }
        }
        if (messages.isEmpty()) {
            throw new McpRuntimeManager.McpRuntimeException("Sampling request carries no text content");
        }
        int maxTokens = request.maxTokens() == null
                ? DEFAULT_SAMPLING_TOKENS : Math.min(request.maxTokens(), maxSamplingTokens());
        String routedModel = routedModel(request, aiMode, activeModelId);
        ChatOptions options = samplingOptions(baseOptions, request, maxTokens, routedModel);
        ChatResponse response = model.call(new Prompt(messages, options));
        String text = response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                ? null : response.getResult().getOutput().getText();
        if (text == null) text = "";
        String servedModel = routedModel != null ? routedModel : modelLabel;
        return McpSchema.CreateMessageResult.builder(
                McpSchema.Role.ASSISTANT,
                new McpSchema.TextContent(text),
                servedModel == null ? "fengyu" : servedModel).build();
    }

    /**
     * Per-request options MUST ride the ACTIVE backend's own options type: Spring AI's
     * {@code OpenAiChatModel} hard-casts prompt options to {@code OpenAiChatOptions}
     * (Ollama likewise to {@code OllamaOptions}), so a generic {@code DefaultChatOptions}
     * dies with {@code ClassCastException} at call time — surfaced in the 4.1.0 联调
     * (green-credit-agent sampling uplink, 2026-11). Mutating the transport's
     * {@code baseOptions} preserves the concrete provider type; the generic builder
     * remains only as the (rare) null-baseOptions fallback.
     */
    static ChatOptions samplingOptions(ToolCallingChatOptions baseOptions,
            McpSchema.CreateMessageRequest request, int maxTokens, String routedModel) {
        Double temperature = request.temperature();
        List<String> stops = request.stopSequences();
        if (baseOptions != null) {
            ToolCallingChatOptions.Builder<?> b = baseOptions.mutate();
            b.maxTokens(maxTokens);
            if (temperature != null) b.temperature(temperature);
            if (routedModel != null) b.model(routedModel);
            if (stops != null && !stops.isEmpty()) b.stopSequences(stops);
            return b.build();
        }
        ChatOptions.Builder b = ChatOptions.builder().maxTokens(maxTokens);
        if (temperature != null) b.temperature(temperature);
        if (routedModel != null) b.model(routedModel);
        if (stops != null && !stops.isEmpty()) b.stopSequences(stops);
        return b.build();
    }

    /**
     * Resolves the model a sample runs on from {@code modelPreferences.hints}, or {@code null}
     * to keep the active backend's own model. Hints arrive priority-ordered (the MCP contract:
     * the list order IS the preference); the first servable one wins.
     * <ul>
     *   <li>local (Ollama) mode — the first hint passes through: the runtime hosts whatever
     *       it has pulled, and an unknown id surfaces as a sampling error rather than a
     *       silent model swap;</li>
     *   <li>cloud modes — a hint is honored only when it names the configured model id:
     *       anything else would send the stored key's provider a model it may not host, so
     *       the request still serves, on the configured model, and reports it honestly.</li>
     * </ul>
     */
    static String routedModel(McpSchema.CreateMessageRequest request, String aiMode, String activeModelId) {
        List<String> hints = new ArrayList<>();
        if (request.modelPreferences() != null && request.modelPreferences().hints() != null) {
            for (McpSchema.ModelHint hint : request.modelPreferences().hints()) {
                if (hint != null && hint.name() != null && !hint.name().isBlank()) hints.add(hint.name());
            }
        }
        if (hints.isEmpty() || activeModelId == null || activeModelId.isBlank()) return null;
        if ("local".equals(aiMode)) return hints.get(0);
        for (String hint : hints) {
            if (hint.equalsIgnoreCase(activeModelId)) return activeModelId;
        }
        return null;
    }

    /**
     * Page sampling tool loop (`sampling.tools`): one model call that may end with the
     * model asking the PAGE to run one of its declared tools. Spring AI 2.x {@code
     * ChatModel.call} never auto-executes tool callbacks (the execution loop is the
     * caller's), so the tool definitions ride the options purely as DECLARATIONS and
     * the raw tool calls come straight back — the page executes them and re-samples
     * with the appended {@code tool_use}/{@code tool_result} blocks, driving its own loop.
     */
    static UiLoopResult sampleToolLoop(ChatModel model, UiLoopRequest request, String modelLabel,
            ToolCallingChatOptions baseOptions, String aiMode, String activeModelId) {
        List<UiLoopMessage> loopMessages = request.messages() == null ? List.of() : request.messages();
        if (loopMessages.size() > maxSamplingMessages()) {
            throw new McpRuntimeManager.McpRuntimeException(
                    "Sampling request exceeds the " + maxSamplingMessages() + " message cap");
        }
        List<ToolCallback> toolCallbacks = pageToolCallbacks(request.tools());
        List<Message> messages = new ArrayList<>();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(new SystemMessage(clamp(request.systemPrompt())));
        }
        for (UiLoopMessage loopMessage : loopMessages) {
            if (loopMessage == null || loopMessage.blocks() == null || loopMessage.blocks().isEmpty()) {
                continue;
            }
            boolean assistant = "assistant".equalsIgnoreCase(loopMessage.role());
            List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            for (UiLoopBlock block : loopMessage.blocks()) {
                if (block == null) continue;
                switch (block.kind() == null ? "" : block.kind()) {
                    case "text" -> {
                        if (block.text() != null && !block.text().isBlank()) {
                            messages.add(assistant
                                    ? (Message) new AssistantMessage(clamp(block.text()))
                                    : (Message) new UserMessage(clamp(block.text())));
                        }
                    }
                    // tool_use is assistant-side by construction (the model's own request).
                    case "tool_use" -> toolCalls.add(new AssistantMessage.ToolCall(
                            block.id() == null ? "" : block.id(), "function",
                            block.name() == null ? "" : block.name(), toJson(block.input())));
                    // tool_result is user-side: the page answers a toolUseId.
                    case "tool_result" -> toolResponses.add(new ToolResponseMessage.ToolResponse(
                            block.toolUseId() == null ? "" : block.toolUseId(), "",
                            clamp(block.text() == null ? "" : block.text())));
                    default -> log.debug("Dropping unknown page sampling block kind: {}", block.kind());
                }
            }
            // Providers require one assistant message carrying all its tool calls (and
            // one tool-response message carrying all its results) per exchange.
            if (!toolCalls.isEmpty()) {
                messages.add(AssistantMessage.builder()
                        .content("")
                        .toolCalls(toolCalls)
                        .build());
            }
            if (!toolResponses.isEmpty()) {
                messages.add(ToolResponseMessage.builder().responses(toolResponses).build());
            }
        }
        if (messages.isEmpty()) {
            throw new McpRuntimeManager.McpRuntimeException("Sampling request carries no content");
        }
        int maxTokens = request.maxTokens() == null
                ? DEFAULT_SAMPLING_TOKENS : Math.min(request.maxTokens(), maxSamplingTokens());
        ChatOptions options = loopOptions(baseOptions, maxTokens, request.temperature(),
                request.toolChoice(), toolCallbacks);
        ChatResponse response = model.call(new Prompt(messages, options));
        List<UiLoopToolCall> toolCalls = new ArrayList<>();
        String text = "";
        if (response != null && response.getResults() != null) {
            for (Generation generation : response.getResults()) {
                if (generation == null || generation.getOutput() == null) continue;
                AssistantMessage output = generation.getOutput();
                if (output.getText() != null && !output.getText().isEmpty() && text.isEmpty()) {
                    text = output.getText();
                }
                if (output.getToolCalls() != null) {
                    for (AssistantMessage.ToolCall call : output.getToolCalls()) {
                        if (call == null || call.name() == null || call.name().isBlank()) continue;
                        toolCalls.add(new UiLoopToolCall(call.id(), call.name(), fromJson(call.arguments())));
                    }
                }
            }
        }
        String servedModel = modelLabel == null ? "fengyu" : modelLabel;
        if (!toolCalls.isEmpty()) {
            return new UiLoopResult(text, servedModel, "toolUse", toolCalls);
        }
        return new UiLoopResult(text, servedModel, "endTurn", List.of());
    }

    /** Page tool declarations as never-executed {@link ToolCallback}s (schema carriers). */
    private static List<ToolCallback> pageToolCallbacks(List<UiLoopTool> tools) {
        if (tools == null || tools.isEmpty()) return List.of();
        if (tools.size() > MAX_LOOP_TOOLS) {
            throw new McpRuntimeManager.McpRuntimeException(
                    "Sampling tool loop exceeds the " + MAX_LOOP_TOOLS + " tool cap");
        }
        List<ToolCallback> callbacks = new ArrayList<>();
        for (UiLoopTool tool : tools) {
            if (tool == null || tool.name() == null || !TOOL_NAME_PATTERN.matcher(tool.name()).matches()) {
                throw new McpRuntimeManager.McpRuntimeException("Invalid sampling tool name");
            }
            String schema = tool.inputSchema() == null || tool.inputSchema().isEmpty()
                    ? "{\"type\":\"object\",\"properties\":{}}" : toJson(tool.inputSchema());
            if (schema.length() > MAX_TOOL_SCHEMA_CHARS) {
                throw new McpRuntimeManager.McpRuntimeException(
                        "Sampling tool schema exceeds the " + MAX_TOOL_SCHEMA_CHARS + " char cap");
            }
            var definition = ToolDefinition.builder()
                    .name(tool.name())
                    .inputSchema(schema);
            if (tool.description() != null && !tool.description().isBlank()) {
                definition.description(clamp(tool.description()));
            } else {
                definition.description("Page tool " + tool.name());
            }
            ToolDefinition built = definition.build();
            callbacks.add(new ToolCallback() {
                @Override public ToolDefinition getToolDefinition() { return built; }
                // Spring AI 2.x ChatModel.call never executes callbacks; the page runs
                // its own tools. Reaching this body would be a framework regression.
                @Override public String call(String toolInput) {
                    throw new UnsupportedOperationException("Page sampling tools execute in the page");
                }
            });
        }
        return callbacks;
    }

    /** Options for the loop call: base-options mutation plus tool declarations. */
    static ChatOptions loopOptions(ToolCallingChatOptions baseOptions, int maxTokens,
            Double temperature, String toolChoice, List<ToolCallback> toolCallbacks) {
        if (baseOptions != null) {
            ToolCallingChatOptions.Builder<?> b = baseOptions.mutate();
            b.maxTokens(maxTokens);
            if (temperature != null) b.temperature(temperature);
            if (!toolCallbacks.isEmpty()) b.toolCallbacks(toolCallbacks);
            applyToolChoice(b, toolChoice);
            return b.build();
        }
        ToolCallingChatOptions.Builder<?> b = ToolCallingChatOptions.builder();
        b.maxTokens(maxTokens);
        if (temperature != null) b.temperature(temperature);
        if (!toolCallbacks.isEmpty()) b.toolCallbacks(toolCallbacks);
        applyToolChoice(b, toolChoice);
        return b.build();
    }

    /**
     * Tool choice rides the provider builder only where the concrete type exposes it
     * (the generic interface does not); other providers keep their default (auto).
     */
    private static void applyToolChoice(ToolCallingChatOptions.Builder<?> builder, String toolChoice) {
        if (toolChoice == null || toolChoice.isBlank()) return;
        String choice = toolChoice.trim().toLowerCase();
        if (!choice.equals("auto") && !choice.equals("none") && !choice.equals("required")) return;
        if (builder instanceof org.springframework.ai.openai.OpenAiChatOptions.Builder openai) {
            openai.toolChoice(choice);
        }
    }

    private static String toJson(Map<String, Object> value) {
        if (value == null) return "{}";
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception malformed) {
            return "{}";
        }
    }

    private static Map<String, Object> fromJson(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return JSON.readValue(value, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception malformed) {
            // The page gets the raw arguments as a single escaped field rather than nothing.
            return Map.of("_raw", value);
        }
    }

    /** A user message whose only content is the image; null when the payload is unusable. */
    private static Message mediaMessage(McpSchema.ImageContent image) {
        byte[] data;
        try {
            data = Base64.getDecoder().decode(image.data());
        } catch (IllegalArgumentException invalid) {
            log.debug("Dropping undecodable sampling image: {}", invalid.toString());
            return null;
        }
        String mimeType = image.mimeType() == null ? "image/png" : image.mimeType();
        Media media = Media.builder()
                .mimeType(MimeTypeUtils.parseMimeType(mimeType))
                .data(new ByteArrayResource(data))
                .name("mcp-sampling-image")
                .build();
        return UserMessage.builder().text("").media(List.of(media)).build();
    }

    /** Live-read sampling budgets: system property first, then env, then the default. */
    static int maxSamplingTokens() {
        return intSetting(MAX_TOKENS_PROPERTY, MAX_TOKENS_ENV, DEFAULT_MAX_SAMPLING_TOKENS, 1, 1_048_576);
    }

    static int maxSamplingMessages() {
        return intSetting(MAX_MESSAGES_PROPERTY, MAX_MESSAGES_ENV, DEFAULT_MAX_SAMPLING_MESSAGES, 1, 4_096);
    }

    static int maxMessageChars() {
        return intSetting(MAX_MESSAGE_CHARS_PROPERTY, MAX_MESSAGE_CHARS_ENV, DEFAULT_MAX_MESSAGE_CHARS,
                128, 8 * 1024 * 1024);
    }

    private static int intSetting(String property, String env, int defaultValue, int min, int max) {
        String raw = System.getProperty(property);
        if (raw == null || raw.isBlank()) raw = System.getenv(env);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            int parsed = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException malformed) {
            return defaultValue;
        }
    }

    private static String clamp(String value) {
        return value.length() > maxMessageChars() ? value.substring(0, maxMessageChars()) : value;
    }
}
