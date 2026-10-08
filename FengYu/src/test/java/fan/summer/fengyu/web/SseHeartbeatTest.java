package fan.summer.fengyu.web;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared SSE comment heartbeat: beats while running, dies on stop, never restarts. */
class SseHeartbeatTest {

    @Test
    void beatsWhileAttachedAndDiesOnStop() {
        AtomicInteger comments = new AtomicInteger();
        SseHeartbeat heartbeat = new SseHeartbeat(Duration.ofMillis(50), comments::incrementAndGet);

        heartbeat.start();
        await().atMost(Duration.ofSeconds(2)).until(() -> comments.get() >= 3);

        heartbeat.stop();
        await().atMost(Duration.ofSeconds(2)).until(() -> !heartbeat.isAlive());
        int afterStop = comments.get();
        // A stopped heartbeat must not fire again (nor may stop leak an unhandled throw).
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertTrue(afterStop <= comments.get() && comments.get() - afterStop <= 1,
                "no further beats after stop: before=" + afterStop + " after=" + comments.get());
    }

    @Test
    void stopIsIdempotentAndSafeBeforeStart() {
        SseHeartbeat heartbeat = new SseHeartbeat(Duration.ofMillis(50), () -> {});
        heartbeat.stop();          // before start — must not throw on the unstarted thread
        heartbeat.start();
        heartbeat.stop();
        heartbeat.stop();          // second stop — must not throw
        await().atMost(Duration.ofSeconds(2)).until(() -> !heartbeat.isAlive());
    }
}
