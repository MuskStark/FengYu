package fan.summer.fengyu.web;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One SSE comment heartbeat on its own virtual thread. Intermediate HTTP stacks and webviews
 * can reap an otherwise-idle SSE connection (an agent run paused on an approval gate, a plugin
 * that has not logged in minutes); a periodic comment frame keeps the transport alive without
 * delivering a client-visible event. Extracted from {@code AiController.SseCallback}'s and
 * {@code NotificationController.NotificationStream}'s inline heartbeats so the agent run
 * stream and the per-plugin log stream can share the same pattern.
 *
 * <p>Single-shot lifecycle: {@link #start()} once, {@link #stop()} once (idempotent). The
 * send action must be safe to call concurrently with regular event sends — SSE comment
 * frames are transparent to {@code EventSource}, and Spring's emitter serializes individual
 * sends — and should itself trigger the stream's normal dead-client cleanup when it fails.
 */
public final class SseHeartbeat {

    private final Duration interval;
    private final Runnable sendComment;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final Thread thread;

    public SseHeartbeat(Duration interval, Runnable sendComment) {
        this.interval = interval;
        this.sendComment = sendComment;
        this.thread = Thread.ofVirtual().name("sse-heartbeat").unstarted(this::loop);
    }

    public void start() {
        thread.start();
    }

    /** Stops the loop and interrupts a parked sleep; safe to call more than once. */
    public void stop() {
        if (stopped.compareAndSet(false, true)) thread.interrupt();
    }

    /** Test visibility: whether the heartbeat thread is still running. */
    public boolean isAlive() {
        return thread.isAlive();
    }

    private void loop() {
        while (!stopped.get()) {
            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (stopped.get()) return;
            sendComment.run();
        }
    }
}
