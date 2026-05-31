package fan.summer.api.ai;

import java.util.List;

/**
 * A single message in an AI chat conversation.
 */
public record AiChatMessage(
    Role role,
    String content,
    List<AiToolCall> toolCalls,
    String toolCallId,
    String toolName,
    String reasoningContent
) {

    public enum Role {
        SYSTEM, USER, ASSISTANT, TOOL
    }

    public AiChatMessage {
        if (toolCalls == null) toolCalls = List.of();
    }

    public AiChatMessage(Role role, String content) {
        this(role, content, List.of(), null, null, null);
    }

    public static AiChatMessage system(String content) {
        return new AiChatMessage(Role.SYSTEM, content);
    }

    public static AiChatMessage user(String content) {
        return new AiChatMessage(Role.USER, content);
    }

    public static AiChatMessage assistant(String content) {
        return new AiChatMessage(Role.ASSISTANT, content);
    }

    public static AiChatMessage assistantWithReasoning(String content, String reasoningContent) {
        return new AiChatMessage(Role.ASSISTANT, content, List.of(), null, null, reasoningContent);
    }

    public static AiChatMessage assistantWithTools(String content, List<AiToolCall> toolCalls) {
        return new AiChatMessage(Role.ASSISTANT, content, toolCalls, null, null, null);
    }

    public static AiChatMessage assistantWithToolsAndReasoning(String content, List<AiToolCall> toolCalls, String reasoningContent) {
        return new AiChatMessage(Role.ASSISTANT, content, toolCalls, null, null, reasoningContent);
    }

    public static AiChatMessage toolResult(String toolCallId, String toolName, String content) {
        return new AiChatMessage(Role.TOOL, content, List.of(), toolCallId, toolName, null);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public boolean hasReasoningContent() {
        return reasoningContent != null && !reasoningContent.isEmpty();
    }
}
