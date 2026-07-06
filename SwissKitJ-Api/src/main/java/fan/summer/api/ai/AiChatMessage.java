package fan.summer.api.ai;

import java.util.List;
import java.util.Objects;

/**
 * An immutable record representing a single message in an AI chat conversation.
 *
 * <p>Each message has a {@link Role}, text content, and optional fields for tool calls,
 * tool results, and reasoning content. Factory methods are provided for each common
 * message variant.</p>
 *
 * @param role              the sender role (system, user, assistant, or tool)
 * @param content           the text content of the message; may be empty for assistant messages
 *                          that carry only tool calls
 * @param toolCalls         tool calls requested by the assistant; empty list for non-tool-call
 *                          messages (never {@code null} after compact constructor normalization)
 * @param toolCallId        the ID of the tool call this message responds to; non-null only
 *                          when {@code role == TOOL}
 * @param toolName          the name of the tool this message responds to; non-null only
 *                          when {@code role == TOOL}
 * @param reasoningContent  optional chain-of-thought content from reasoning models
 *                          (e.g. DeepSeek-R1); may be {@code null}
 * @see AiToolCall
 * @see AiToolResult
 */
public record AiChatMessage(
    Role role,
    String content,
    List<AiToolCall> toolCalls,
    String toolCallId,
    String toolName,
    String reasoningContent
) {

    /**
     * The role of a message author in the conversation.
     *
     * <ul>
     *   <li>{@link #SYSTEM} — high-level instructions that shape assistant behaviour</li>
     *   <li>{@link #USER} — input from the human user</li>
     *   <li>{@link #ASSISTANT} — model-generated response, possibly containing tool calls</li>
     *   <li>{@link #TOOL} — result returned by a tool execution, fed back to the model</li>
     * </ul>
     */
    public enum Role {
        SYSTEM, USER, ASSISTANT, TOOL
    }

    /**
     * Compact constructor — validates and normalises components:
     * {@code role} must be non-null; a null {@code content} becomes {@code ""};
     * a null {@code toolCalls} becomes an empty list.
     * <p>Note: {@code toolCallId} is intentionally NOT enforced for {@link Role#TOOL}
     * messages — local Hermes-format backends (Qwen3) may emit tool calls without IDs.
     */
    public AiChatMessage {
        Objects.requireNonNull(role, "role must not be null");
        if (content == null) content = "";
        if (toolCalls == null) toolCalls = List.of();
    }

    /**
     * Creates a simple message with the given role and content, no tool calls or reasoning.
     *
     * @param role    the message role
     * @param content the text content
     */
    public AiChatMessage(Role role, String content) {
        this(role, content, List.of(), null, null, null);
    }

    /**
     * Creates a system-instruction message.
     *
     * @param content the system prompt text
     * @return a new SYSTEM message
     */
    public static AiChatMessage system(String content) {
        return new AiChatMessage(Role.SYSTEM, content);
    }

    /**
     * Creates a user-input message.
     *
     * @param content the user's input text
     * @return a new USER message
     */
    public static AiChatMessage user(String content) {
        return new AiChatMessage(Role.USER, content);
    }

    /**
     * Creates a plain assistant response message with no tool calls or reasoning.
     *
     * @param content the assistant's response text
     * @return a new ASSISTANT message
     */
    public static AiChatMessage assistant(String content) {
        return new AiChatMessage(Role.ASSISTANT, content);
    }

    /**
     * Creates an assistant message that includes reasoning content (chain-of-thought).
     *
     * @param content           the visible response text
     * @param reasoningContent  the raw reasoning/thinking text
     * @return a new ASSISTANT message with reasoning
     */
    public static AiChatMessage assistantWithReasoning(String content, String reasoningContent) {
        return new AiChatMessage(Role.ASSISTANT, content, List.of(), null, null, reasoningContent);
    }

    /**
     * Creates an assistant message that requests one or more tool calls.
     *
     * @param content   the assistant's text (may be empty)
     * @param toolCalls the tool calls the model wants to invoke
     * @return a new ASSISTANT message with tool calls
     */
    public static AiChatMessage assistantWithTools(String content, List<AiToolCall> toolCalls) {
        return new AiChatMessage(Role.ASSISTANT, content, toolCalls, null, null, null);
    }

    /**
     * Creates an assistant message that includes both tool calls and reasoning content.
     *
     * @param content           the assistant's text (may be empty)
     * @param toolCalls         the tool calls the model wants to invoke
     * @param reasoningContent  the raw reasoning/thinking text
     * @return a new ASSISTANT message with tool calls and reasoning
     */
    public static AiChatMessage assistantWithToolsAndReasoning(String content, List<AiToolCall> toolCalls, String reasoningContent) {
        return new AiChatMessage(Role.ASSISTANT, content, toolCalls, null, null, reasoningContent);
    }

    /**
     * Creates a tool-result message that feeds the output of a tool execution back to the model.
     *
     * @param toolCallId the ID of the tool call this result corresponds to
     * @param toolName   the name of the tool that was executed
     * @param content    the tool's output text
     * @return a new TOOL message
     */
    public static AiChatMessage toolResult(String toolCallId, String toolName, String content) {
        return new AiChatMessage(Role.TOOL, content, List.of(), toolCallId, toolName, null);
    }

    /**
     * Returns {@code true} if this message contains at least one tool call.
     *
     * @return true if tool calls are present
     */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /**
     * Returns {@code true} if this message contains non-empty reasoning content.
     *
     * @return true if reasoning content is present
     */
    public boolean hasReasoningContent() {
        return reasoningContent != null && !reasoningContent.isEmpty();
    }
}
