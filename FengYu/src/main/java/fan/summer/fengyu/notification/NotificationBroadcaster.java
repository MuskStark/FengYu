package fan.summer.fengyu.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Fans each freshly persisted {@link NotificationView} out to the live SSE subscribers of
 * {@code GET /api/notifications/stream}.
 *
 * <p><b>Decoupled broadcast.</b> {@link #broadcast} never invokes a subscriber's callback on
 * the caller's thread. Each subscriber owns a bounded queue drained by its own virtual
 * thread, so a slow SSE client (or one whose {@code SseEmitter.send()} blocks on network
 * I/O) can only fall behind its own queue — never stall the thread that produced the
 * notification (an HTTP request thread for the plugin {@code notify} bridge, or the agent
 * runner's virtual thread on run termination). This mirrors {@code PluginLogStore}'s
 * subscriber model at the scale notifications deserve: far fewer events, same guarantees.
 *
 * <p>When a subscriber's queue fills (it has fallen badly behind), the OLDEST entry is
 * evicted; the subscriber keeps receiving the newest notifications and memory stays
 * bounded. Missed entries are not lost — they are already persisted, and the client refetches
 * the list whenever its stream (re)connects.
 */
@Service
public class NotificationBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(NotificationBroadcaster.class);

    /** Per-subscriber bounded queue; notifications are rare, so overflow means a dead client. */
    public static final int SUBSCRIBER_QUEUE_CAPACITY = 128;

    private final List<SubscriberQueue> subscribers = new CopyOnWriteArrayList<>();

    /**
     * Register a live subscriber. Returns an unsubscribe handle the SSE endpoint must invoke
     * on emitter completion/error/timeout so a dead client does not accumulate.
     */
    public Runnable subscribe(Consumer<NotificationView> subscriber) {
        SubscriberQueue queue = new SubscriberQueue(subscriber);
        subscribers.add(queue);
        queue.start();
        return () -> {
            subscribers.remove(queue);
            queue.stop();
        };
    }

    /** Deliver one view to every live subscriber without ever blocking the caller. */
    public void broadcast(NotificationView view) {
        for (SubscriberQueue queue : subscribers) queue.offer(view);
    }

    /** Package-private observability for lifecycle tests. */
    int subscriberCountForTest() {
        return subscribers.size();
    }

    /** One live subscriber's bounded queue + dedicated virtual-thread drainer. */
    private static final class SubscriberQueue {
        private final Consumer<NotificationView> subscriber;
        private final LinkedBlockingDeque<NotificationView> queue =
                new LinkedBlockingDeque<>(SUBSCRIBER_QUEUE_CAPACITY);
        private final AtomicBoolean running = new AtomicBoolean(true);
        private volatile Thread drainer;

        SubscriberQueue(Consumer<NotificationView> subscriber) {
            this.subscriber = subscriber;
        }

        void start() {
            if (!running.get()) return;
            drainer = Thread.ofVirtual().name("notification-sub").start(this::drain);
        }

        /** Non-blocking enqueue; on overflow evict the OLDEST pending entry. */
        void offer(NotificationView view) {
            while (running.get() && !queue.offerLast(view)) {
                if (queue.pollFirst() == null) break;
            }
        }

        void stop() {
            if (running.compareAndSet(true, false)) {
                queue.clear();
                Thread thread = drainer;
                if (thread != null) thread.interrupt();
            }
        }

        private void drain() {
            try {
                while (running.get()) {
                    NotificationView view;
                    try {
                        view = queue.takeFirst();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (!running.get()) break;
                    try {
                        subscriber.accept(view);
                    } catch (RuntimeException e) {
                        // A subscriber throwing (e.g. emitter already completed) must not
                        // poison delivery to itself or other subscribers.
                        log.debug("Notification subscriber failed: {}", e.getMessage());
                    }
                }
            } finally {
                running.set(false);
            }
        }
    }
}
