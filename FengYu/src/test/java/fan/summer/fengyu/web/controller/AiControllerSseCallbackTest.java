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
                new AiController.SseCallback(emitter, terminal::incrementAndGet, terminal::incrementAndGet, disconnected::incrementAndGet);

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
                new AiController.SseCallback(emitter, () -> {}, () -> {}, disconnected::incrementAndGet);

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
                new AiController.SseCallback(emitter, terminal::incrementAndGet, terminal::incrementAndGet, disconnected::incrementAndGet);

        callback.onComplete("done", 1, 1.0);

        assertEquals(1, terminal.get());
        assertEquals(0, disconnected.get());
    }

    /** A failed model turn must take the failure terminal — never export partial outputs. */
    @Test
    void modelErrorRunsTheFailureTerminalNotTheSuccessOne() {
        TestEmitter emitter = new TestEmitter();
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AiController.SseCallback callback =
                new AiController.SseCallback(emitter, completed::incrementAndGet, failed::incrementAndGet, () -> {});

        callback.onError(new IllegalStateException("boom"));

        assertEquals(0, completed.get());
        assertEquals(1, failed.get());
    }

    /** The lease runs exactly one terminal action however often the paths race. */
    @Test
    void leaseRunsExactlyOneTerminalAction() {
        fan.summer.fengyu.ai.ChatFileGrantService grants =
                org.mockito.Mockito.mock(fan.summer.fengyu.ai.ChatFileGrantService.class);
        java.util.List<fan.summer.fengyu.ai.ChatFileGrantService.StagedOutput> staged = java.util.List.of();
        AiController.TurnLease lease = new AiController.TurnLease(grants, staged);

        lease.complete();
        lease.abort();
        lease.complete();

        org.mockito.Mockito.verify(grants).exportStaging(staged);
        org.mockito.Mockito.verify(grants, org.mockito.Mockito.never()).discardStaging(org.mockito.ArgumentMatchers.any());

        AiController.TurnLease aborted = new AiController.TurnLease(grants, staged);
        aborted.abort();
        aborted.abort();
        aborted.complete();
        org.mockito.Mockito.verify(grants, org.mockito.Mockito.times(1)).discardStaging(staged);
        org.mockito.Mockito.verify(grants, org.mockito.Mockito.times(1)).exportStaging(staged);
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
