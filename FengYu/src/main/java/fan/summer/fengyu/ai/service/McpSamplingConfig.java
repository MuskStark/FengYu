package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.ChatBackend;
import fan.summer.fengyu.ai.mcp.McpRuntimeManager;
import fan.summer.fengyu.ai.service.AiModeService;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges MCP Apps sampling callbacks to the ACTIVE chat backend. An MCP server (or a sandbox
 * page through the UI controller) may ask the host's model to complete a prompt; this wiring
 * resolves whichever backend {@link AiModeService} currently serves — cloud or local Ollama —
 * at REQUEST time, so mode switches take effect for the next sample without a reconnect.
 *
 * <p>Budgets are hard caps, not preferences: at most {@link #MAX_SAMPLING_MESSAGES} messages
 * with {@link #MAX_MESSAGE_CHARS} each, completing at most {@link #MAX_SAMPLING_TOKENS} new
 * tokens. The whole feature stays dark until the operator opts in via
 * {@code fengyu.mcp.ui.sampling} (see {@code McpRuntimeManager#samplingEnabled}).
 */
@Configuration
public class McpSamplingConfig {

    private static final Logger log = LoggerFactory.getLogger(McpSamplingConfig.class);
    static final int MAX_SAMPLING_MESSAGES = 40;
    static final int MAX_MESSAGE_CHARS = 64 * 1024;
    static final int MAX_SAMPLING_TOKENS = 2048;
    static final int DEFAULT_SAMPLING_TOKENS = 512;

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
        List<McpSchema.SamplingMessage> samplingMessages = request.messages() == null
                ? List.of() : request.messages();
        if (samplingMessages.size() > MAX_SAMPLING_MESSAGES) {
            throw new McpRuntimeManager.McpRuntimeException(
                    "Sampling request exceeds the " + MAX_SAMPLING_MESSAGES + " message cap");
        }
        List<Message> messages = new ArrayList<>();
        String systemPrompt = request.systemPrompt();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new SystemMessage(clamp(systemPrompt)));
        }
        for (McpSchema.SamplingMessage samplingMessage : samplingMessages) {
            String text = textOf(samplingMessage.content());
            if (text == null) continue;
            // The MCP sampling role set is {user, assistant}; system context rides the
            // dedicated systemPrompt field above.
            messages.add(samplingMessage.role() == McpSchema.Role.ASSISTANT
                    ? (Message) new AssistantMessage(text)
                    : (Message) new UserMessage(text));
        }
        if (messages.isEmpty()) {
            throw new McpRuntimeManager.McpRuntimeException("Sampling request carries no text content");
        }
        int maxTokens = request.maxTokens() == null
                ? DEFAULT_SAMPLING_TOKENS : Math.min(request.maxTokens(), MAX_SAMPLING_TOKENS);
        ChatOptions options = request.temperature() == null
                ? ChatOptions.builder().maxTokens(maxTokens).build()
                : ChatOptions.builder().maxTokens(maxTokens).temperature(request.temperature()).build();
        ChatResponse response = model.call(new Prompt(messages, options));
        String text = response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                ? null : response.getResult().getOutput().getText();
        if (text == null) text = "";
        return McpSchema.CreateMessageResult.builder(
                McpSchema.Role.ASSISTANT,
                new McpSchema.TextContent(text),
                modelLabel == null ? "fengyu" : modelLabel).build();
    }

    private static String clamp(String value) {
        return value.length() > MAX_MESSAGE_CHARS ? value.substring(0, MAX_MESSAGE_CHARS) : value;
    }

    /** Text blocks only, for now: an image-bearing sampling request degrades to its text parts. */
    private static String textOf(McpSchema.Content content) {
        if (content instanceof McpSchema.TextContent textContent && textContent.text() != null) {
            return clamp(textContent.text());
        }
        log.debug("Dropping non-text sampling content block: {}", content == null ? "null" : content.type());
        return null;
    }
}
