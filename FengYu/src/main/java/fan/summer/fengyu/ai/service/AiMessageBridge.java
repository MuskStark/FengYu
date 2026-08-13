package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.util.JsonHelper;
import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiMedia;
import fan.summer.fengyu.ai.AiToolCall;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * One-way mapper from FengYu {@link AiChatMessage} to Spring AI {@link Message}, used by
 * the chat backends to seed each {@code Prompt} from the conversation history.
 *
 * <p>This is the slim successor to the deleted bidirectional mapper: it only does the
 * FengYu→Spring AI direction the tool loop needs (tool-call extraction now happens
 * directly off the streamed {@link AssistantMessage} via Spring AI's own API, so the
 * reverse direction is gone).
 *
 * <p>Role mapping:
 * <ul>
 *   <li>{@code SYSTEM}    → {@link SystemMessage}</li>
 *   <li>{@code USER}      → {@link UserMessage}</li>
 *   <li>{@code ASSISTANT} → {@link AssistantMessage} (with optional {@code ToolCall}s)</li>
 *   <li>{@code TOOL}      → {@link ToolResponseMessage}</li>
 * </ul>
 *
 * <p>Tool-call arguments cross the boundary as JSON strings (Spring AI's
 * {@code ToolCall.arguments()} is a JSON string), serialised via {@link JsonHelper}.
 */
final class AiMessageBridge {

    private AiMessageBridge() {}

    /** FengYu message → Spring AI message. */
    static Message toSpringAi(AiChatMessage src) {
        String text = src.content() == null ? "" : src.content();
        return switch (src.role()) {
            case SYSTEM -> new SystemMessage(text);
            case USER   -> userMessage(text, src.media());
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
     * A tool response must remain a {@link ToolResponseMessage} for provider protocol ordering,
     * while Spring AI 2.0 only accepts {@link Media} on a {@link UserMessage}. For image-bearing
     * tool results, emit the protocol response first and a short user media part immediately
     * after it. Text-only messages retain the original one-message mapping.
     */
    static List<Message> toSpringAiMessages(AiChatMessage src) {
        Message primary = toSpringAi(src);
        if (src.role() != AiChatMessage.Role.TOOL || src.media().isEmpty()) return List.of(primary);
        return List.of(primary, userMessage(
                "Visual output from tool " + (src.toolName() == null ? "browser" : src.toolName()) + ".",
                src.media()));
    }

    private static UserMessage userMessage(String text, List<AiMedia> media) {
        if (media == null || media.isEmpty()) return new UserMessage(text);
        List<Media> springMedia = new ArrayList<>(media.size());
        for (AiMedia item : media) {
            byte[] data;
            try {
                data = Base64.getDecoder().decode(item.base64Data());
            } catch (IllegalArgumentException invalid) {
                continue;
            }
            springMedia.add(Media.builder()
                    .mimeType(MimeTypeUtils.parseMimeType(item.mimeType()))
                    .data(new ByteArrayResource(data))
                    .name(item.name())
                    .build());
        }
        return springMedia.isEmpty() ? new UserMessage(text)
                : UserMessage.builder().text(text).media(springMedia).build();
    }
}
