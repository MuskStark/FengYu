package fan.summer.fengyu.plugin.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Captures plugin worker log lines into a bounded per-plugin ring buffer and notifies live SSE
 * subscribers in real time. Without this the {@code plugin-<id>-stderr} drain in
 * {@link PluginProcessManager} re-logs each line to the host log file and then throws it away, so
 * there was no way to retrieve a plugin's recent output except by reading {@code .fengyu/logs/...}.
 *
 * <p><b>Decoupled broadcast.</b> {@code append()} never calls a subscriber's {@code accept()} on the
 * caller's thread. Each subscriber owns a bounded {@link SubscriberQueue} drained by its own virtual
 * thread, so a slow SSE client (or one whose {@code SseEmitter.send()} blocks on network I/O) can
 * only fall behind its own queue — never block the worker stderr drain thread that drives
 * {@code append()}. When a subscriber's queue fills, the OLDEST entry is evicted (matching the
 * ring-buffer eviction philosophy for the history buffer); the subscriber keeps streaming the newest
 * lines and the store stays bounded.
 *
 * <p><b>Bounded memory.</b> Each plugin keeps at most {@link #CAPACITY} history entries (oldest
 * evicted first). Entries belonging to a plugin that has never been seen (no worker spawned yet)
 * simply return empty lists from {@link #recent(String, int)}.
 */
@Service
public class PluginLogStore {
    private static final Logger log = LoggerFactory.getLogger(PluginLogStore.class);

    /** Ring-buffer size per plugin; ~500 short log lines is a few hundred KB worst case. */
    public static final int CAPACITY = 500;
    /**
     * Per-subscriber bounded queue size. Each SSE connection gets its own queue drained by its own
     * virtual thread; when full the oldest entry is dropped so a slow client never blocks the stderr
     * drain and never grows memory unbounded.
     */
    public static final int SUBSCRIBER_QUEUE_CAPACITY = 256;
    private final Clock clock;
    /** Store-wide monotonic sequence so the SSE stream can replay history and go live without duplicates. */
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, Deque<PluginLogEntry>> buffers = new ConcurrentHashMap<>();
    private final Map<String, List<SubscriberQueue>> subscribers = new ConcurrentHashMap<>();

    public PluginLogStore() {
        this(Clock.systemUTC());
    }

    /** Test seam so {@link PluginLogStoreTest} can assert ordering deterministically. */
    PluginLogStore(Clock clock) {
        this.clock = clock;
    }

    /** Append a captured line and notify any live subscribers. {@code message} should already be redacted. */
    public void append(String pluginId, String level, String message) {
        if (pluginId == null || message == null) return;
        PluginLogEntry entry = new PluginLogEntry(Instant.now(clock), level == null ? PluginLogEntry.DEFAULT_LEVEL : level, message, sequence.incrementAndGet());
        Deque<PluginLogEntry> buffer = buffers.computeIfAbsent(pluginId, ignored -> new ArrayDeque<>(CAPACITY));
        List<SubscriberQueue> snapshot;
        synchronized (buffer) {
            if (buffer.size() >= CAPACITY) buffer.removeFirst();
            buffer.addLast(entry);
            // Snapshot the subscriber list under the buffer lock so a subscriber added during the
            // drain window still gets this entry enqueued, and so we never iterate a list being mutated.
            snapshot = copySubscribers(pluginId);
        }
        // Enqueue to each subscriber's OWN bounded queue (never call accept() here). This is O(subscribers)
        // and lock-free per-queue, so the stderr drain thread returns immediately regardless of how slow
        // any SSE client is.
        for (SubscriberQueue queue : snapshot) queue.offer(entry);
    }

    /** The most recent up-to-{@code max} entries for a plugin, oldest first. Empty if none. */
    public List<PluginLogEntry> recent(String pluginId, int max) {
        if (pluginId == null) return List.of();
        Deque<PluginLogEntry> buffer = buffers.get(pluginId);
        if (buffer == null) return List.of();
        int limit = max <= 0 ? CAPACITY : Math.min(max, CAPACITY);
        synchronized (buffer) {
            if (buffer.size() <= limit) return List.copyOf(buffer);
            List<PluginLogEntry> out = new ArrayList<>(limit);
            int skip = buffer.size() - limit;
            int i = 0;
            for (PluginLogEntry entry : buffer) {
                if (i++ >= skip) out.add(entry);
            }
            return out;
        }
    }

    /**
     * Register a live subscriber that receives future appended entries. Returns a runnable that
     * unregisters it; callers (the SSE endpoint) must invoke it on emitter error/timeout/completion
     * so a dead client doesn't accumulate. Delivery happens on the subscriber's own virtual thread
     * via a bounded queue (drop-oldest on overflow), so the subscriber's {@code accept()} — even if
     * it blocks — never stalls the worker stderr drain.
     */
    public Runnable subscribe(String pluginId, Consumer<PluginLogEntry> subscriber) {
        SubscriberQueue queue = new SubscriberQueue(pluginId, subscriber);
        subscribers.computeIfAbsent(pluginId, ignored -> new ArrayList<>());
        List<SubscriberQueue> list = subscribers.get(pluginId);
        synchronized (list) {
            list.add(queue);
        }
        queue.start();
        return () -> {
            synchronized (list) {
                list.remove(queue);
            }
            queue.stop();
        };
    }

    /** Drop all buffered entries and subscribers for a plugin (used on uninstall, not on worker restart). */
    public void clear(String pluginId) {
        List<SubscriberQueue> list = subscribers.remove(pluginId);
        if (list != null) {
            synchronized (list) {
                for (SubscriberQueue queue : list) queue.stop();
            }
        }
        buffers.remove(pluginId);
    }

    private List<SubscriberQueue> copySubscribers(String pluginId) {
        List<SubscriberQueue> list = subscribers.get(pluginId);
        if (list == null || list.isEmpty()) return List.of();
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    /**
     * One live subscriber's bounded queue + dedicated drain thread. Decouples how fast the host
     * appends (stderr drain thread) from how fast the subscriber consumes (a slow SSE client). On
     * overflow the oldest entry is dropped (see {@link #offer}); the subscriber keeps running and
     * memory stays bounded.
     */
    private final class SubscriberQueue {
        private final String pluginId;
        private final Consumer<PluginLogEntry> subscriber;
        private final LinkedBlockingDeque<PluginLogEntry> queue = new LinkedBlockingDeque<>(SUBSCRIBER_QUEUE_CAPACITY);
        private final AtomicBoolean running = new AtomicBoolean(true);
        private Thread drainer;

        SubscriberQueue(String pluginId, Consumer<PluginLogEntry> subscriber) {
            this.pluginId = pluginId;
            this.subscriber = subscriber;
        }

        /** Start the dedicated virtual-thread drainer. */
        void start() {
            drainer = Thread.ofVirtual().name("plugin-log-sub-" + pluginId).start(this::drain);
        }

        /**
         * Enqueue an entry for this subscriber. If the bounded queue is full (the subscriber has
         * fallen behind), evict the OLDEST entry to make room — a slow client keeps streaming the
         * newest lines and never blocks the caller.
         */
        void offer(PluginLogEntry entry) {
            while (running.get() && !queue.offerLast(entry)) {
                // Queue full: drop the oldest pending entry (the subscriber is behind). This keeps the
                // newest data flowing to the client and bounds memory, mirroring the history ring buffer.
                if (queue.pollFirst() == null) break;
            }
        }

        /** Signal the drainer to stop and clear any pending entries. Idempotent. */
        void stop() {
            if (running.compareAndSet(true, false)) {
                queue.clear();
                if (drainer != null) drainer.interrupt();
            }
        }

        private void drain() {
            try {
                while (running.get()) {
                    PluginLogEntry entry;
                    try {
                        entry = queue.takeFirst();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (!running.get()) break;
                    try {
                        subscriber.accept(entry);
                    } catch (RuntimeException e) {
                        // A subscriber throwing must not poison delivery to itself or other subscribers.
                        log.debug("Plugin log subscriber failed: {}", e.getMessage());
                    }
                }
            } finally {
                running.set(false);
            }
        }
    }
}
