package fan.summer.fengyu.ai.session;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mid-turn steering input, keyed by conversation: short user messages parked while the
 * conversation's turn is still GENERATING, drained by {@code ToolLoopDriver} at its
 * round boundaries and appended to the live conversation as ordinary user messages —
 * the model sees them on its very next round instead of after the whole turn ends.
 *
 * <p>The queue is deliberately tiny and cheap: steering is a message-shaped nudge, not
 * a turn. It carries no attachments, resources, or permission context (the regular
 * send queue owns full turns); a steered message inherits the running turn's already-
 * resolved workspace, tools, and approval state. That is also why the caps are small —
 * {@link #MAX_PENDING} entries of {@link #MAX_BYTES} UTF-8 bytes each: a steering
 * message exists to redirect ("stop, use the other file"), not to carry a document.</p>
 *
 * <p>Lifecycle: entries offered while a turn streams are drained by that turn. A turn
 * that fails with entries still parked leaves them for the conversation's NEXT turn
 * (the words still mean something to it); a transport disconnect clears them, because
 * the client that sent them is gone and its UI already showed them as delivered into
 * that turn. The map itself holds one deque per conversation that ever steered, so its
 * footprint is bounded by live conversations, not by traffic.</p>
 */
public final class TurnSteering {

    /** Pending steering messages per conversation before new offers are rejected. */
    public static final int MAX_PENDING = 3;

    /** Per-message UTF-8 byte cap (parity with the skill-body budget's byte-rationale). */
    public static final int MAX_BYTES = 16 * 1024;

    private static final Map<Long, ArrayDeque<String>> QUEUES = new ConcurrentHashMap<>();

    private TurnSteering() { }

    /**
     * Parks one steering message for {@code conversationId}.
     *
     * @return {@code true} when parked; {@code false} when the pending cap is full
     *         (the sender should surface that instead of silently dropping words)
     */
    public static boolean offer(Long conversationId, String text) {
        if (conversationId == null || text == null || text.isBlank()) return false;
        ArrayDeque<String> queue = QUEUES.computeIfAbsent(conversationId, id -> new ArrayDeque<>());
        synchronized (queue) {
            if (queue.size() >= MAX_PENDING) return false;
            queue.addLast(text);
            return true;
        }
    }

    /**
     * Removes and returns every parked steering message of {@code conversationId}, in
     * send order. Called by the turn loop at its round boundary; an empty result is the
     * common case and must stay allocation-free on that path.
     *
     * <p>Empty deques are never removed from the map: removing one would race a
     * concurrent {@link #offer} that already holds the deque reference (its message
     * would land in an orphaned deque and vanish). One empty deque per conversation
     * that ever steered is a footprint bounded by live conversations.</p>
     */
    public static List<String> drain(Long conversationId) {
        if (conversationId == null) return List.of();
        ArrayDeque<String> queue = QUEUES.get(conversationId);
        if (queue == null) return List.of();
        synchronized (queue) {
            if (queue.isEmpty()) return List.of();
            List<String> drained = new ArrayList<>(queue);
            queue.clear();
            return drained;
        }
    }

    /** Drops every parked steering message (transport disconnect: the sender is gone). */
    public static void clear(Long conversationId) {
        if (conversationId == null) return;
        ArrayDeque<String> queue = QUEUES.remove(conversationId);
        if (queue != null) synchronized (queue) { queue.clear(); }
    }

    /** Whether one message would fit the byte cap (checked before {@link #offer}). */
    public static boolean withinByteCap(String text) {
        return text != null && text.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }
}
