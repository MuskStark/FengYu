package fan.summer.zhiflow.ai.adapter;

import fan.summer.zhiflow.ai.util.JsonHelper;
import fan.summer.zhiflow.api.ai.AiChatMessage;
import fan.summer.zhiflow.api.ai.AiToolCall;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bidirectional mapper between ZhiFlow's {@link AiChatMessage} and Spring AI's
 * {@link Message} hierarchy. Replaces the LangChain4j {@code ChatMessageMapper}.
 *
 * <p>Role mapping:
 * <ul>
 *   <li>{@code SYSTEM}    ↔ {@link SystemMessage}</li>
 *   <li>{@code USER}      ↔ {@link UserMessage}</li>
 *   <li>{@code ASSISTANT} ↔ {@link AssistantMessage} (with optional {@code ToolCall}s)</li>
 *   <li>{@code TOOL}      ↔ {@link ToolResponseMessage}</li>
 * </ul>
 *
 * <p>Tool-call arguments cross the boundary as JSON strings (Spring AI's
 * {@code ToolCall.arguments()} is a JSON string), serialised via {@link JsonHelper}.
 *
 * <p>Spring AI 2.0 GA notes: {@code AssistantMessage} is built via its
 * {@code builder()} (no public 3-arg ctor), and {@code ToolResponseMessage.ToolResponse}
 * exposes {@code responseData()} (not {@code responseMessage()}).
 */
public final class MessageMapper {

    private MessageMapper() {}

    /** ZhiFlow message → Spring AI message. */
    public static Message toSpringAi(AiChatMessage src) {
        String text = src.content() == null ? "" : src.content();
        return switch (src.role()) {
            case SYSTEM -> new SystemMessage(text);
            case USER   -> new UserMessage(text);
            case ASSISTANT -> {
                if (src.toolCalls() == null || src.toolCalls().isEmpty()) {
                    yield new AssistantMessage(text);
                }
                List<AssistantMessage.ToolCall> tcs = new ArrayList<>();
                for (AiToolCall tc : src.toolCalls()) {
                    tcs.add(new AssistantMessage.ToolCall(
                            tc.id() != null ? tc.id() : "",
                            "function",
                            tc.name(),
                            JsonHelper.toJson(tc.arguments())));
                }
                yield AssistantMessage.builder()
                        .content(text)
                        .properties(Map.of())
                        .toolCalls(tcs)
                        .build();
            }
            case TOOL -> ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            src.toolCallId() != null ? src.toolCallId() : "",
                            src.toolName() != null ? src.toolName() : "",
                            text)))
                    .build();
        };
    }

    /**
     * Extract tool-call requests from a Spring AI {@link AssistantMessage} into
     * ZhiFlow {@link AiToolCall}s. Used by the manual tool loop after a streamed
     * response completes with pending tool calls.
     */
    public static List<AiToolCall> extractToolCalls(AssistantMessage am) {
        if (!am.hasToolCalls()) return List.of();
        List<AiToolCall> out = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
            String id = tc.id() != null && !tc.id().isEmpty()
                    ? tc.id()
                    : "tc_" + System.currentTimeMillis();
            Map<String, Object> args = parseArgs(tc.arguments());
            out.add(AiToolCall.of(id, tc.name(), args));
        }
        return out;
    }

    private static Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JsonHelper.parseObject(json);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
