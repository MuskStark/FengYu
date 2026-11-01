package fan.summer.fengyu.ai.session;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Completed-background-exec notifications, keyed by conversation: when a
 * {@code workspace_exec background=true} process exits, its completion note lands here
 * and {@code ToolLoopDriver} drains it at the next round boundary — the model learns
 * its long-running task finished and can collect the output, without the turn having
 * blocked on it (the task-notification pattern).
 *
 * <p>Notes are one-way and ephemeral: consumed at the next round top of the owning
 * conversation, never persisted (the user already got the notification-center event;
 * the rollout records the world as history needs it). A conversation that never turns
 * again simply never reads them; the per-conversation deque is bounded and cleared on
 * transport disconnect.</p>
 */
public final class BackgroundNotes {

    /** Notes kept per conversation before new completions drop the oldest. */
    public static final int MAX_PER_CONVERSATION = 8;

    private static final Map<Long, ArrayDeque<String>> NOTES = new ConcurrentHashMap<>();

    private BackgroundNotes() { }

    /** Parks one completion note; evicts the oldest beyond the cap (newest news wins). */
    public static void offer(Long conversationId, String note) {
        if (conversationId == null || note == null || note.isBlank()) return;
        ArrayDeque<String> queue = NOTES.computeIfAbsent(conversationId, id -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addLast(note);
            while (queue.size() > MAX_PER_CONVERSATION) queue.removeFirst();
        }
    }

    /**
     * Removes and returns the conversation's parked notes, oldest first. Empty results
     * stay allocation-free. Like {@link TurnSteering#drain}: deques are never removed
     * from the map (removal would race a concurrent offer holding the reference), so the
     * footprint is one bounded deque per conversation that ever ran background work.
     */
    public static List<String> drain(Long conversationId) {
        if (conversationId == null) return List.of();
        ArrayDeque<String> queue = NOTES.get(conversationId);
        if (queue == null) return List.of();
        synchronized (queue) {
            if (queue.isEmpty()) return List.of();
            List<String> drained = new ArrayList<>(queue);
            queue.clear();
            return drained;
        }
    }

    /** Drops parked notes (transport disconnect: nobody is left to read them). */
    public static void clear(Long conversationId) {
        if (conversationId == null) return;
        ArrayDeque<String> queue = NOTES.remove(conversationId);
        if (queue != null) synchronized (queue) { queue.clear(); }
    }
}
