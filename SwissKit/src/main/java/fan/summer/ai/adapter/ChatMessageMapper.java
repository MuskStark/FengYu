package fan.summer.ai.adapter;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import fan.summer.ai.util.JsonHelper;
import fan.summer.api.ai.AiChatMessage;
import fan.summer.api.ai.AiToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bidirectional mapper between SwissKitJ's {@link AiChatMessage} and LangChain4j's
 * {@link ChatMessage} hierarchy.
 *
 * <p>Role mapping:
 * <ul>
 *   <li>{@link AiChatMessage.Role#SYSTEM} ↔ {@link SystemMessage}</li>
 *   <li>{@link AiChatMessage.Role#USER} ↔ {@link UserMessage}</li>
 *   <li>{@link AiChatMessage.Role#ASSISTANT} ↔ {@link AiMessage} (with optional tool requests)</li>
 *   <li>{@link AiChatMessage.Role#TOOL} ↔ {@link ToolExecutionResultMessage}</li>
 * </ul>
 *
 * <p>Note on tool-call arguments: {@link AiToolCall#arguments()} is a
 * {@code Map<String,Object>} (SwissKitJ's internal representation), whereas
 * LangChain4j's {@link ToolExecutionRequest#arguments()} is a JSON string. This
 * mapper serializes/deserializes via {@link JsonHelper} (Gson) on the boundary.
 */
public final class ChatMessageMapper {

    private ChatMessageMapper() {}

    /** Converts a SwissKitJ message to its LangChain4j equivalent. */
    public static ChatMessage toLc4j(AiChatMessage src) {
        String text = src.content() == null ? "" : src.content();
        return switch (src.role()) {
            case SYSTEM -> SystemMessage.from(text);
            case USER   -> UserMessage.from(text);
            case ASSISTANT -> {
                if (src.toolCalls() == null || src.toolCalls().isEmpty()) {
                    yield AiMessage.from(text);
                }
                List<ToolExecutionRequest> reqs = new ArrayList<>();
                for (AiToolCall tc : src.toolCalls()) {
                    reqs.add(ToolExecutionRequest.builder()
                        .id(tc.id())
                        .name(tc.name())
                        .arguments(JsonHelper.toJson(tc.arguments()))
                        .build());
                }
                yield AiMessage.from(text, reqs);
            }
            case TOOL -> ToolExecutionResultMessage.from(
                src.toolCallId() == null ? "unknown" : src.toolCallId(),
                src.toolName() == null ? "unknown" : src.toolName(),
                text);
        };
    }

    /** Converts a LangChain4j message back to a SwissKitJ message. */
    @SuppressWarnings("unchecked")
    public static AiChatMessage fromLc4j(ChatMessage src) {
        if (src instanceof SystemMessage sm) {
            return AiChatMessage.system(sm.text());
        }
        if (src instanceof UserMessage um) {
            return AiChatMessage.user(um.singleText());
        }
        if (src instanceof AiMessage am) {
            if (am.hasToolExecutionRequests()) {
                List<AiToolCall> calls = new ArrayList<>();
                for (ToolExecutionRequest req : am.toolExecutionRequests()) {
                    Map<String, Object> args = parseArgs(req.arguments());
                    AiToolCall call = AiToolCall.of(req.name(), args);
                    calls.add(call);
                }
                return AiChatMessage.assistantWithTools(am.text(), calls);
            }
            return AiChatMessage.assistant(am.text());
        }
        if (src instanceof ToolExecutionResultMessage tm) {
            return AiChatMessage.toolResult(tm.id(), tm.toolName(), tm.text());
        }
        throw new IllegalArgumentException("Unsupported LangChain4j message type: " + src.getClass());
    }

    /** Parses a JSON arguments string into a Map; returns an empty map on null/blank input. */
    private static Map<String, Object> parseArgs(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        Map<String, Object> parsed = JsonHelper.parseObject(argumentsJson);
        return parsed == null ? Map.of() : parsed;
    }
}
