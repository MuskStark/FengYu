package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Lifecycle regression for the notification SSE stream: when the emitter terminates (client
 * disconnect, error, timeout) the heartbeat thread must be interrupted immediately instead of
 * parking until its next 25s tick — the finished→interrupt linkage mirrors
 * {@code AiController.SseCallback}.
 */
class NotificationControllerTest {

    private static final class TestEmitter extends SseEmitter {
        private Runnable completion;
        private Consumer<Throwable> error;

        TestEmitter() {
            super(0L);
        }

        @Override
        public void onCompletion(Runnable callback) {
            this.completion = callback;
        }

        @Override
        public void onError(Consumer<Throwable> callback) {
            this.error = callback;
        }

        void fireCompletion() {
            if (completion != null) completion.run();
        }

        void fireError(Throwable cause) {
            if (error != null) error.accept(cause);
        }
    }

    @SuppressWarnings("unchecked")
    private static NotificationService subscribedService(AtomicInteger unsubscribed) {
        NotificationService service = mock(NotificationService.class);
        when(service.subscribe(any(Consumer.class))).thenReturn(unsubscribed::incrementAndGet);
        return service;
    }

    @Test
    void emitterCompletionInterruptsTheHeartbeatImmediatelyAndUnsubscribesOnce() throws Exception {
        TestEmitter emitter = new TestEmitter();
        NotificationController.NotificationStream stream =
                new NotificationController.NotificationStream(emitter);
        AtomicInteger unsubscribed = new AtomicInteger();

        stream.start(subscribedService(unsubscribed));
        Thread heartbeat = stream.heartbeatThreadForTest();
        assertTrue(heartbeat.isAlive(), "heartbeat should be running while the stream is open");

        emitter.fireCompletion();
        emitter.fireCompletion(); // idempotent: a second terminal callback changes nothing

        heartbeat.join(2000);
        assertFalse(heartbeat.isAlive(),
                "the heartbeat thread must die on emitter termination, not after the next tick");
        assertEquals(1, unsubscribed.get(), "the broadcaster callback must be released exactly once");
    }

    @Test
    void anEmitterErrorUnsubscribesAndStopsTheHeartbeat() throws Exception {
        TestEmitter emitter = new TestEmitter();
        NotificationController.NotificationStream stream =
                new NotificationController.NotificationStream(emitter);
        AtomicInteger unsubscribed = new AtomicInteger();

        stream.start(subscribedService(unsubscribed));

        emitter.fireError(new IOException("client closed"));

        stream.heartbeatThreadForTest().join(2000);
        assertFalse(stream.heartbeatThreadForTest().isAlive(),
                "the heartbeat must be interrupted on emitter error, not parked until its tick");
        assertEquals(1, unsubscribed.get());
    }
}
