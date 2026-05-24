package fan.summer.ai.session;

import fan.summer.api.ai.AiChatMessage;
import fan.summer.api.ai.AiToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Multi-turn conversation manager.
 * Tracks message history with support for tool-call messages and provides
 * token-based history trimming to stay within context limits.
 */
public class ChatSession {

    private static final Logger log = LoggerFactory.getLogger(ChatSession.class);

    private final List<AiChatMessage> history = new ArrayList<>();
    private final int maxHistoryRounds;

    /**
     * @param maxHistoryRounds max number of user/assistant conversation rounds to keep.
     *                         A round = one user + one assistant message.
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

    public void add(AiChatMessage message) {
        history.add(message);
        log.debug("add: role={}, contentLength={}, historySize={}",
                  message.role(), message.content() != null ? message.content().length() : 0, history.size());
        trim();
    }

    public void addUser(String content) {
        add(AiChatMessage.user(content));
    }

    public void addAssistant(String content) {
        add(AiChatMessage.assistant(content));
    }

    public void addAssistantWithTools(String content, List<AiToolCall> toolCalls) {
        add(AiChatMessage.assistantWithTools(content, toolCalls));
    }

    public void addToolResult(String toolCallId, String toolName, String content) {
        add(AiChatMessage.toolResult(toolCallId, toolName, content));
    }

    public List<AiChatMessage> getHistory() {
        return Collections.unmodifiableList(history);
    }

    public void clear() {
        history.clear();
        log.info("ChatSession cleared");
    }

    public int size() {
        return history.size();
    }

    /**
     * Trim history to stay within maxHistoryRounds.
     * Always preserves the first message if it's a SYSTEM message.
     * Counts user/assistant pairs as rounds and drops oldest rounds first.
     */
    private void trim() {
        if (maxHistoryRounds <= 0) return;

        // Count non-system, non-tool-result messages as rounds
        int rounds = 0;
        for (AiChatMessage msg : history) {
            if (msg.role() == AiChatMessage.Role.USER || msg.role() == AiChatMessage.Role.ASSISTANT) {
                if (msg.role() == AiChatMessage.Role.USER) rounds++;
            }
        }

        while (rounds > maxHistoryRounds && history.size() > 1) {
            int start = hasSystemMessage() ? 1 : 0;
            if (start >= history.size()) break;

            AiChatMessage removed = history.remove(start);
            if (removed.role() == AiChatMessage.Role.USER) {
                rounds--;
            } else if (removed.role() == AiChatMessage.Role.ASSISTANT) {
                // If assistant message removed, also remove any following tool results
                while (start < history.size()
                        && history.get(start).role() == AiChatMessage.Role.TOOL) {
                    history.remove(start);
                }
                // The user message before it is now unmatched, remove it too
                if (start < history.size() && history.get(start).role() == AiChatMessage.Role.USER) {
                    // keep it — the user message starts a new round
                }
            } else if (removed.role() == AiChatMessage.Role.TOOL) {
                // keep trimming tool results
            }
        }
    }

    private boolean hasSystemMessage() {
        return !history.isEmpty() && history.get(0).role() == AiChatMessage.Role.SYSTEM;
    }
}
