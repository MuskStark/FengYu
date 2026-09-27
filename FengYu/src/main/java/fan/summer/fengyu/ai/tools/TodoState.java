package fan.summer.fengyu.ai.tools;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conversation-scoped todo list state behind the {@code todo_write} tool (terminal
 * coding-agent practice: the model keeps a visible plan for long multi-step tasks).
 * In-memory by design — the list is working context, not durable data; after a host
 * restart the conversation simply starts without a list.
 */
@Component
public class TodoState {

    /** One todo step. Status: pending | in_progress | completed. */
    public record TodoItem(String content, String status) {}

    static final int MAX_ITEMS = 50;
    static final int MAX_CONTENT_CHARS = 500;
    private static final List<String> STATUSES = List.of("pending", "in_progress", "completed");

    private final Map<Long, List<TodoItem>> byConversation = new ConcurrentHashMap<>();

    /** Replaces the whole list for {@code conversationId} (normalized + bounded). */
    public List<TodoItem> replace(Long conversationId, List<TodoItem> items) {
        if (conversationId == null) return List.of();
        List<TodoItem> bounded = items == null ? List.of() : items.stream()
                .filter(item -> item != null && item.content() != null && !item.content().isBlank())
                .map(item -> new TodoItem(
                        item.content().length() > MAX_CONTENT_CHARS
                                ? item.content().substring(0, MAX_CONTENT_CHARS) : item.content(),
                        normalize(item.status())))
                .limit(MAX_ITEMS)
                .toList();
        byConversation.put(conversationId, bounded);
        return bounded;
    }

    public List<TodoItem> list(Long conversationId) {
        if (conversationId == null) return List.of();
        return byConversation.getOrDefault(conversationId, List.of());
    }

    public void clearConversation(Long conversationId) {
        if (conversationId != null) byConversation.remove(conversationId);
    }

    private static String normalize(String status) {
        return status != null && STATUSES.contains(status) ? status : "pending";
    }
}
