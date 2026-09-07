package fan.summer.fengyu.ai.session;

import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-turn conversation manager.
 * Tracks message history with support for tool-call messages and provides
 * token-based history trimming to stay within context limits.
 *
 * <p>Thread safety: mutations ({@link #add}, {@link #clear}) and reads
 * ({@link #getHistory}, {@link #size}) synchronize on this instance, and
 * {@link #getHistory()} returns an immutable point-in-time snapshot — a reader
 * iterating the history never races a concurrent append or trim.</p>
 */
public class ChatSession {

    private static final Logger log = LoggerFactory.getLogger(ChatSession.class);

    private final List<AiChatMessage> history = new ArrayList<>();
    private final int maxHistoryRounds;

    /**
     * @param maxHistoryRounds max number of user/assistant conversation rounds to keep.
     *                         A round = one user message plus everything the model
     *                         produced for it (assistant turns and their tool results)
     *                         up to the next user message.
     *                         Set to 0 for unlimited.
     */
    public ChatSession(int maxHistoryRounds) {
        this.maxHistoryRounds = maxHistoryRounds;
        log.info("ChatSession created: maxHistoryRounds={}", maxHistoryRounds);
    }

    public ChatSession() {
        this(20);
        log.info("ChatSession created: maxHistoryRounds=20 (default)");
    }

    /**
     * Appends a message to the history and trims if necessary.
     *
     * @param message the message to add; must not be null
     */
    public synchronized void add(AiChatMessage message) {
        history.add(message);
        log.debug("add: role={}, contentLength={}, historySize={}",
                  message.role(), message.content() != null ? message.content().length() : 0, history.size());
        trim();
    }

    /** Convenience method — appends a USER role message. */
    public void addUser(String content) {
        add(AiChatMessage.user(content));
    }

    /** Convenience method — appends an ASSISTANT role message. */
    public void addAssistant(String content) {
        add(AiChatMessage.assistant(content));
    }

    /**
     * Convenience method — appends an ASSISTANT message with tool calls.
     *
     * @param content   the text content (may be empty)
     * @param toolCalls the tool calls issued by the assistant
     */
    public void addAssistantWithTools(String content, List<AiToolCall> toolCalls) {
        add(AiChatMessage.assistantWithTools(content, toolCalls));
    }

    /**
     * Convenience method — appends a TOOL result message.
     *
     * @param toolCallId the ID of the tool call this result corresponds to
     * @param toolName   the name of the tool that was executed
     * @param content    the tool output text
     */
    public void addToolResult(String toolCallId, String toolName, String content) {
        add(AiChatMessage.toolResult(toolCallId, toolName, content));
    }

    /**
     * Returns an immutable snapshot of the current message history. The returned list
     * never changes under a reader's feet when messages are appended or trimmed
     * concurrently.
     *
     * @return the conversation history
     */
    public synchronized List<AiChatMessage> getHistory() {
        return List.copyOf(history);
    }

    /** Clears all messages from the history. */
    public synchronized void clear() {
        history.clear();
        log.info("ChatSession cleared");
    }

    /**
     * Returns the number of messages currently in the history.
     *
     * @return the history size
     */
    public synchronized int size() {
        return history.size();
    }

    /**
     * Trim history to stay within maxHistoryRounds.
     * Always preserves the first message if it's a SYSTEM message.
     * A round is one USER message plus every message the model produced for it
     * (ASSISTANT tool-call messages and their TOOL results) up to the next USER
     * message; rounds are dropped WHOLE so the remaining history never begins with
     * an orphaned TOOL result or an ASSISTANT tool-call whose results were removed.
     */
    private void trim() {
        if (maxHistoryRounds <= 0) return;

        int rounds = 0;
        for (AiChatMessage msg : history) {
            if (msg.role() == AiChatMessage.Role.USER) rounds++;
        }

        while (rounds > maxHistoryRounds && history.size() > 1) {
            int start = hasSystemMessage() ? 1 : 0;
            if (start >= history.size()) break;

            if (history.get(start).role() != AiChatMessage.Role.USER) {
                // Stragglers before the first complete round (an assistant/tool exchange
                // with no leading user message): drop them one by one.
                history.remove(start);
                continue;
            }
            // Drop [USER … next USER) as one unit — the assistant tool-call messages and
            // their TOOL results live inside the round and keep each other legal.
            int end = start + 1;
            while (end < history.size() && history.get(end).role() != AiChatMessage.Role.USER) {
                end++;
            }
            history.subList(start, end).clear();
            rounds--;
        }
    }

    private boolean hasSystemMessage() {
        return !history.isEmpty() && history.get(0).role() == AiChatMessage.Role.SYSTEM;
    }
}
