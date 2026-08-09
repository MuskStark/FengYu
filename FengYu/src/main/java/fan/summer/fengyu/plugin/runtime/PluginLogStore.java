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
        append(pluginId, level, null, null, message);
    }

    /** Append a structured SDK event, retaining its logger and thread metadata. */
    public void append(String pluginId, String level, String logger, String thread, String message) {
        if (pluginId == null || message == null) return;
        Deque<PluginLogEntry> buffer = buffers.computeIfAbsent(pluginId, ignored -> new ArrayDeque<>(CAPACITY));
        PluginLogEntry entry;
        synchronized (buffer) {
            // Allocate the sequence under the per-plugin buffer lock. Creating it before the lock
            // let two concurrent appenders insert seq=N+1 ahead of seq=N, violating SSE order.
            entry = new PluginLogEntry(Instant.now(clock),
                level == null ? PluginLogEntry.DEFAULT_LEVEL : level,
                logger, thread, message, sequence.incrementAndGet());
            if (buffer.size() >= CAPACITY) buffer.removeFirst();
            buffer.addLast(entry);
            // Snapshot the subscriber list under the buffer lock so a subscriber added during the
            // drain window still gets this entry enqueued, and so we never iterate a list being mutated.
            List<SubscriberQueue> snapshot = copySubscribers(pluginId);
            // Offer while still holding the per-plugin ordering lock. The queues are bounded and
            // offer() is non-blocking, so this cannot wait on a client; keeping it in the critical
            // section prevents concurrent appenders from enqueueing seq=N+1 before seq=N.
            for (SubscriberQueue queue : snapshot) queue.offer(entry);
        }
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
        Subscription subscription = subscribeWithSnapshot(pluginId, subscriber);
        subscription.activate();
        return subscription.unsubscribe();
    }

    /**
     * Atomically register a live subscriber AND snapshot the current history under the SAME buffer
     * lock (P1-8). The returned {@link Subscription} carries the history snapshot (oldest first) and
     * the high-water sequence (the max sequence in that snapshot, or -1 when empty). The subscriber's
     * drainer is deliberately NOT started here. Entries appended after registration accumulate in
     * its bounded FIFO while the controller replays {@link Subscription#snapshot()}; only a later
     * {@link Subscription#activate()} starts live delivery. That replay barrier prevents a live
     * drainer from overtaking history and removes the need for a racy mutable high-water holder.
     */
    public Subscription subscribeWithSnapshot(String pluginId, Consumer<PluginLogEntry> subscriber) {
        SubscriberQueue queue = new SubscriberQueue(pluginId, subscriber);
        Deque<PluginLogEntry> buffer = buffers.computeIfAbsent(pluginId, ignored -> new ArrayDeque<>(CAPACITY));
        List<PluginLogEntry> snapshot;
        long highWater;
        synchronized (buffer) {
            // Register under the buffer lock so an append concurrent with this call either sees the
            // subscriber (and enqueues live) OR is already in the snapshot we hold here — never both,
            // never neither.
            List<SubscriberQueue> list = subscribers.computeIfAbsent(pluginId, ignored -> new ArrayList<>());
            synchronized (list) {
                list.add(queue);
            }
            snapshot = List.copyOf(buffer);
            highWater = snapshot.isEmpty() ? -1L : snapshot.get(snapshot.size() - 1).sequence();
        }
        AtomicBoolean unsubscribed = new AtomicBoolean();
        Runnable unsubscribe = () -> {
            if (!unsubscribed.compareAndSet(false, true)) return;
            List<SubscriberQueue> list = subscribers.get(pluginId);
            if (list != null) {
                synchronized (list) {
                    list.remove(queue);
                }
            }
            queue.stop();
        };
        return new Subscription(snapshot, highWater, queue::start, unsubscribe);
    }

    /** Result of {@link #subscribeWithSnapshot}: the replay snapshot, the high-water sequence, and the unsubscribe handle. */
    public static final class Subscription {
        private final List<PluginLogEntry> snapshot;
        private final long highWater;
        private final Runnable activate;
        private final Runnable unsubscribe;

        Subscription(List<PluginLogEntry> snapshot, long highWater, Runnable activate, Runnable unsubscribe) {
            this.snapshot = snapshot;
            this.highWater = highWater;
            this.activate = activate;
            this.unsubscribe = unsubscribe;
        }
        /** History entries at subscribe time, oldest first (already covers dedup against live). */
        public List<PluginLogEntry> snapshot() { return snapshot; }
        /** Max sequence in the snapshot (-1 when empty); a live entry with sequence ≤ this is a dup. */
        public long highWater() { return highWater; }
        /** Start draining queued live entries. Call only after replaying {@link #snapshot()}. */
        public void activate() { activate.run(); }
        /** Unregister the subscriber (idempotent). */
        public Runnable unsubscribe() { return unsubscribe; }
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

    /** Package-private observability for lifecycle/concurrency tests. */
    int subscriberCountForTest(String pluginId) {
        List<SubscriberQueue> list = subscribers.get(pluginId);
        if (list == null) return 0;
        synchronized (list) {
            return list.size();
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
        private final AtomicBoolean started = new AtomicBoolean();
        private Thread drainer;

        SubscriberQueue(String pluginId, Consumer<PluginLogEntry> subscriber) {
            this.pluginId = pluginId;
            this.subscriber = subscriber;
        }

        /** Start the dedicated virtual-thread drainer. */
        void start() {
            if (!running.get() || !started.compareAndSet(false, true)) return;
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
