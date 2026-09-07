package fan.summer.fengyu.web.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * The SSE heartbeat thread must die when the emitter terminates (completion, timeout,
     * error) instead of parking until its next 10s tick — otherwise every dropped stream
     * leaks a heartbeat that keeps poking a dead emitter.
     */
    @Test
    void emitterTerminationInterruptsTheHeartbeatImmediately() throws Exception {
        TestEmitter emitter = new TestEmitter();
        AiController.SseCallback callback =
                new AiController.SseCallback(emitter, () -> {}, () -> {}, () -> {});
        assertTrue(callback.heartbeatAlive(), "heartbeat runs while the stream is open");

        emitter.fireCompletion();
        waitForHeartbeatDeath(callback);

        // A second termination path (transport error) behaves the same way.
        TestEmitter failing = new TestEmitter();
        AiController.SseCallback errored =
                new AiController.SseCallback(failing, () -> {}, () -> {}, () -> {});
        failing.fireError(new IOException("client closed"));
        waitForHeartbeatDeath(errored);
    }

    /** Polls the (virtual) heartbeat thread until it exits; bounded so a regression fails fast. */
    private static void waitForHeartbeatDeath(AiController.SseCallback callback) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
        while (callback.heartbeatAlive() && System.nanoTime() < deadline) Thread.sleep(10);
        assertFalse(callback.heartbeatAlive(),
                "the heartbeat must be interrupted on emitter termination, not survive until its tick");
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
