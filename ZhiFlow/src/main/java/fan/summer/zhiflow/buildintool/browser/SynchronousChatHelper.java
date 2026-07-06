package fan.summer.zhiflow.buildintool.browser;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import fan.summer.zhiflow.ai.adapter.ChatMessageMapper;
import fan.summer.zhiflow.ai.service.CloudChatBackend;
import fan.summer.zhiflow.api.ai.AiChatMessage;
import fan.summer.zhiflow.api.ai.AiServiceProvider;
import fan.summer.zhiflow.api.ai.ChatBackend;
import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Sync (non-streaming) chat helper used by the browser-automation planner to make
 * a direct, tool-free LLM call. Bypasses {@link ChatBackend#chat} because the
 * browser-automation tool itself is registered as an {@code AiTool}, and going
 * through the normal chat loop would inject the planner's own {@code browser_automate}
 * tool into the request — causing infinite recursion of browser sessions.
 *
 * <p>Currently supports only OpenAI-compatible endpoints (direct
 * {@link OpenAiChatModel} call). Anthropic's Messages API uses a different body
 * shape; a parallel {@code AnthropicChatModel} branch would be needed to support
 * Anthropic-only setups — not implemented here.
 */
public final class SynchronousChatHelper {

    private static final PluginLogger log = LoggerFactory.getLogger(SynchronousChatHelper.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private SynchronousChatHelper() {}

    /**
     * Sends the planner's conversation history to the active cloud backend without
     * any tool definitions and returns the assistant's text reply.
     *
     * <p>The history typically contains a system prompt (browser automation planner
     * prompt), prior user snapshots, and prior assistant actions — maintained by
     * {@code BrowserAutomateTool.runThinkActLoop}. Only SYSTEM/USER/ASSISTANT
     * roles survive the mapper (any TOOL messages map to
     * {@code ToolExecutionResultMessage} which the planner never produces here).
     *
     * @param history the planner conversation history (system + user + assistant messages)
     * @return the assistant's text reply, or {@code null} if no cloud backend is
     *     active, the backend is not OpenAI-compatible, or the call fails
     */
    public static String call(List<AiChatMessage> history) {
        ChatBackend service = AiServiceProvider.getService().orElse(null);
        if (service == null || !service.isReady()) {
            log.warn("No AI service active; browser planner cannot proceed");
            return null;
        }
        if (!(service instanceof CloudChatBackend cloud)) {
            log.warn("Browser planner requires a cloud backend, got: {}", service.getClass().getSimpleName());
            return null;
        }

        // Only OpenAI-compatible HTTP format is supported for the planner's direct call.
        // Anthropic's /v1/messages uses a different body shape; not implemented here.
        if (cloud.provider() != CloudChatBackend.Provider.OPENAI) {
            log.warn("Browser planner only supports OpenAI-compatible endpoints for direct call, got: {}",
                     cloud.provider());
            return null;
        }

        String endpoint = cloud.getEndpoint();
        String apiKey = cloud.getApiKey();
        String model = cloud.getModelNameInternal();
        if (endpoint == null || endpoint.isBlank() || apiKey == null || apiKey.isBlank()
            || model == null || model.isBlank()) {
            log.warn("Browser planner: cloud backend not fully configured");
            return null;
        }

        try {
            ChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl(endpoint)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(0.3)   // Low temperature for consistent planner output
                .maxTokens(512)     // Planner only needs short JSON responses
                .timeout(TIMEOUT)
                .build();

            // Convert SwissKitJ history → LC4j ChatMessage list (no tool definitions
            // are attached to the ChatRequest, so the model returns plain text/JSON).
            List<ChatMessage> messages = new ArrayList<>(history.size());
            for (AiChatMessage msg : history) {
                messages.add(ChatMessageMapper.toLc4j(msg));
            }

            ChatRequest request = ChatRequest.builder()
                .messages(messages)
                .build();

            ChatResponse response = chatModel.chat(request);
            AiMessage ai = response.aiMessage();
            String text = ai.text();
            if (text == null || text.isBlank()) {
                log.warn("Planner returned empty content");
                return null;
            }
            log.debug("Planner response: {}", text.length() > 200 ? text.substring(0, 200) + "..." : text);
            return text.trim();
        } catch (Exception e) {
            log.error("Browser planner sync chat failed", e);
            return null;
        }
    }
}
