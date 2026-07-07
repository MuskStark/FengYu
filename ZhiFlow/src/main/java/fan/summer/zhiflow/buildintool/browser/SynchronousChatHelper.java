package fan.summer.zhiflow.buildintool.browser;

import fan.summer.zhiflow.ai.adapter.MessageMapper;
import fan.summer.zhiflow.ai.service.SpringAiCloudBackend;
import fan.summer.zhiflow.ai.spring.AiSpringContext;
import fan.summer.zhiflow.api.ai.AiChatMessage;
import fan.summer.zhiflow.api.ai.AiServiceProvider;
import fan.summer.zhiflow.api.ai.ChatBackend;
import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * Sync (non-streaming) chat helper used by the browser-automation planner to make
 * a direct, tool-free LLM call. Bypasses {@link ChatBackend#chat} because the
 * browser-automation tool itself is registered as an {@code AiTool}, and going
 * through the normal chat loop would inject the planner's own {@code browser_automate}
 * tool into the request — causing infinite recursion of browser sessions.
 *
 * <p>Uses the Spring AI {@code openAiChatModel} bean directly ({@link ChatModel#call(Prompt)}),
 * with no {@code ToolCallback}s attached, so the model returns plain text/JSON. Only
 * OpenAI-compatible endpoints are supported; Anthropic uses a different body shape
 * and is not wired for the planner's direct call.
 */
public final class SynchronousChatHelper {

    private static final PluginLogger log = LoggerFactory.getLogger(SynchronousChatHelper.class);

    private SynchronousChatHelper() {}

    /**
     * Sends the planner's conversation history to the active cloud backend without
     * any tool definitions and returns the assistant's text reply.
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
        if (!(service instanceof SpringAiCloudBackend cloud)) {
            log.warn("Browser planner requires a cloud backend, got: {}", service.getClass().getSimpleName());
            return null;
        }

        // Only OpenAI-compatible endpoints are supported for the planner's direct call.
        if (cloud.provider() != SpringAiCloudBackend.Provider.OPENAI) {
            log.warn("Browser planner only supports OpenAI-compatible endpoints for direct call, got: {}",
                     cloud.provider());
            return null;
        }

        try {
            // Reuse the pre-configured openAiChatModel bean (base URL / API key / model
            // are baked into the client at context start). No ToolCallbacks are attached,
            // so the model returns plain text — no tool loop, no recursion.
            ChatModel chatModel = AiSpringContext.getBean("openAiChatModel", ChatModel.class);

            List<Message> messages = new ArrayList<>(history.size());
            for (AiChatMessage msg : history) {
                messages.add(MessageMapper.toSpringAi(msg));
            }

            ChatResponse response = chatModel.call(new Prompt(messages));
            String text = response != null && response.getResult() != null
                    && response.getResult().getOutput() != null
                    ? response.getResult().getOutput().getText()
                    : null;
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
