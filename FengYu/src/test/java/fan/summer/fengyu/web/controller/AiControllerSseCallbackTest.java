package fan.summer.fengyu.web.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AiControllerSseCallbackTest {

    @Test
    void emitterCompletionDisconnectsAndSuppressesBackendStartAndLaterModelCompletion() throws Exception {
        TestEmitter emitter = new TestEmitter();
        AtomicInteger terminal = new AtomicInteger();
        AtomicInteger disconnected = new AtomicInteger();
        AiController.SseCallback callback =
                new AiController.SseCallback(emitter, terminal::incrementAndGet, disconnected::incrementAndGet);

        emitter.fireCompletion();
        AtomicInteger starts = new AtomicInteger();
        assertFalse(callback.start(starts::incrementAndGet));
        callback.onComplete("late", 1, 1.0);

        assertEquals(0, starts.get());
        assertEquals(0, terminal.get());
        assertEquals(1, disconnected.get());
    }

    @Test
    void failedInitialSendDisconnectsOnlyOnce() {
        TestEmitter emitter = new TestEmitter();
        emitter.failSend = true;
        AtomicInteger disconnected = new AtomicInteger();
        AiController.SseCallback callback =
                new AiController.SseCallback(emitter, () -> {}, disconnected::incrementAndGet);

        assertFalse(callback.open());
        emitter.fireError(new IOException("client closed"));

        assertEquals(1, disconnected.get());
    }

    @Test
    void normalModelCompletionRunsTerminalWithoutDisconnecting() {
        TestEmitter emitter = new TestEmitter();
        AtomicInteger terminal = new AtomicInteger();
        AtomicInteger disconnected = new AtomicInteger();
        AiController.SseCallback callback =
                new AiController.SseCallback(emitter, terminal::incrementAndGet, disconnected::incrementAndGet);

        callback.onComplete("done", 1, 1.0);

        assertEquals(1, terminal.get());
        assertEquals(0, disconnected.get());
    }

    private static final class TestEmitter extends SseEmitter {
        private Runnable completion;
        private Consumer<Throwable> error;
        private boolean failSend;

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

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failSend) throw new IOException("client closed");
        }

        @Override
        public void complete() {
            fireCompletion();
        }

        void fireCompletion() {
            if (completion != null) completion.run();
        }

        void fireError(Throwable failure) {
            if (error != null) error.accept(failure);
        }
    }
}
