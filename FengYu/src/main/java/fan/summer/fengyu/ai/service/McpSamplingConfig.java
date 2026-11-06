package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.AiConfigService;
import fan.summer.fengyu.ai.ChatBackend;
import fan.summer.fengyu.ai.mcp.McpRuntimeManager;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

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

    @Bean
    public McpSamplingBinding mcpSamplingBinding(McpRuntimeManager runtime, AiModeService modes) {
        runtime.setSamplingHandler(request -> {
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
            return sample(transport.chatModel(), request, transport.modelLabel());
        });
        return new McpSamplingBinding();
    }

    /** Marker bean so the wiring is visible in diagnostics; carries no behavior. */
    public static final class McpSamplingBinding {}

    static McpSchema.CreateMessageResult sample(ChatModel model, McpSchema.CreateMessageRequest request,
                                                String modelLabel) {
        return sample(model, request, modelLabel, AiConfigService.getAiMode(),
                AiConfigService.activeModelId(), AiConfigService.activeModelSupportsImages());
    }

    static McpSchema.CreateMessageResult sample(ChatModel model, McpSchema.CreateMessageRequest request,
                                                String modelLabel, String aiMode, String activeModelId,
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
        ChatOptions.Builder options = ChatOptions.builder().maxTokens(maxTokens);
        if (request.temperature() != null) options.temperature(request.temperature());
        if (routedModel != null) options.model(routedModel);
        if (request.stopSequences() != null && !request.stopSequences().isEmpty()) {
            options.stopSequences(request.stopSequences());
        }
        ChatResponse response = model.call(new Prompt(messages, options.build()));
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
