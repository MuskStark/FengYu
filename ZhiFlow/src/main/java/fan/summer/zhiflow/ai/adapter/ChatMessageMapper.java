package fan.summer.zhiflow.ai.adapter;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import fan.summer.zhiflow.ai.util.JsonHelper;
import fan.summer.zhiflow.api.ai.AiChatMessage;
import fan.summer.zhiflow.api.ai.AiToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bidirectional mapper between ZhiFlow's {@link AiChatMessage} and LangChain4j's
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
 * {@code Map<String,Object>} (ZhiFlow's internal representation), whereas
 * LangChain4j's {@link ToolExecutionRequest#arguments()} is a JSON string. This
 * mapper serializes/deserializes via {@link JsonHelper} (Gson) on the boundary.
 */
public final class ChatMessageMapper {

    private ChatMessageMapper() {}

    /** Converts a ZhiFlow message to its LangChain4j equivalent. */
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
                Objects.requireNonNull(src.toolCallId(), "toolCallId"),
                Objects.requireNonNull(src.toolName(), "toolName"),
                text);
        };
    }

    /** Converts a LangChain4j message back to a ZhiFlow message. */
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
                    // Preserve the server-issued tool-call ID: Anthropic requires
                    // tool_result.tool_use_id to match the original tool_use.id,
                    // so round-trip AiChatMessage → LC4j → AiChatMessage must not
                    // regenerate the id. Fall back to a synthetic id only when the
                    // provider omitted one.
                    String id = req.id() != null && !req.id().isEmpty()
                        ? req.id()
                        : "tc_" + System.currentTimeMillis();
                    AiToolCall call = AiToolCall.of(id, req.name(), args);
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
