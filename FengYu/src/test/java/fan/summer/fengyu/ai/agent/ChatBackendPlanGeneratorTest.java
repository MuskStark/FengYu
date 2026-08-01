package fan.summer.fengyu.ai.agent;

import fan.summer.fengyu.ai.service.AiModeService;
import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiServiceException;
import fan.summer.fengyu.ai.AiStreamCallback;
import fan.summer.fengyu.ai.ChatBackend;
import fan.summer.fengyu.ai.ChatFileContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatBackendPlanGeneratorTest {

    @Test
    void parsesFencedWorkflowAndNormalizesIndexes() {
        ToolCallback tool = new AgentRunnerTest.EchoToolCallback();
        String response = """
                ```json
                {
                  "goal": "echo twice",
                  "reasoning": "chain outputs",
                  "steps": [
                    {"index": 9, "toolName": "echo", "args": {"text": "hi"},
                     "description": "first", "requiresApproval": false},
                    {"index": 12, "toolName": "echo",
                     "args": {"text": "{{steps.0.result}}"},
                     "description": "second", "requiresApproval": true}
                  ]
                }
                ```
                """;

        AgentPlan plan = ChatBackendPlanGenerator.parseAndValidate(
                response, "requested", List.of(tool));

        assertEquals("echo twice", plan.goal());
        assertEquals(2, plan.steps().size());
        assertEquals(0, plan.steps().get(0).index());
        assertEquals(1, plan.steps().get(1).index());
        assertEquals("{{steps.0.result}}", plan.steps().get(1).args().get("text"));
    }

    @Test
    void rejectsUnknownTool() {
        String response = """
                {"steps":[{"toolName":"missing","args":{},"description":"bad"}]}
                """;

        assertThrows(IllegalArgumentException.class, () ->
                ChatBackendPlanGenerator.parseAndValidate(
                        response, "requested", List.of(new AgentRunnerTest.EchoToolCallback())));
    }

    /**
     * Regression test for the planner-timeout deadlock: when the model never completes the
     * planning stream (simulated by a backend whose chatWithoutTools never fires the callback),
     * the generator must give up after the timeout, cancel the in-flight generation, and leave
     * the backend with {@code generating == false} so subsequent requests are not wedged.
     *
     * <p>Uses a 1-second planning timeout (test seam) so the test does not wait the full 180s.
     */
    @Test
    void timeoutCancelsStuckBackendAndReleasesLock() throws Exception {
        HungBackend backend = new HungBackend();
        AiModeService modeService = new AiModeService();
        modeService.setService(backend);

        // 1s timeout keeps the test fast while still exercising the real timeout/cancel path.
        ChatBackendPlanGenerator generator =
                new ChatBackendPlanGenerator(modeService, 1);

        assertThrows(IllegalStateException.class, () ->
                generator.generate("do something", List.of(new AgentRunnerTest.EchoToolCallback()), null));

        // The planner gave up; cancelGeneration() must have been invoked, so the backend
        // must no longer report an in-progress generation. Without the fix this stays true
        // forever (the lock leaks), which is exactly the wedge we are guarding against.
        assertTrue(backend.cancelled, "planner timeout should call backend.cancelGeneration()");
        // Wait for the cancelled worker to finish its cleanup (finally-block clears the flag).
        // The cancellation is asynchronous (interrupt releases the blocked worker), so the flag
        // is cleared shortly after cancelGeneration() returns — join the worker to avoid a race
        // between the interrupt and the assertion.
        backend.awaitWorkerCleared();
        assertFalse(backend.isGenerating(),
                "generating flag must be released after a timed-out planning call");
    }

    /**
     * A minimal {@link ChatBackend} that simulates a model stream that never completes:
     * chatWithoutTools sets {@code generating = true} and starts a worker that blocks forever
     * (mimicking a hung Ollama process / stalled provider connection). Only
     * {@link #cancelGeneration()} releases the lock — exactly the path the planner must take.
     */
    static final class HungBackend implements ChatBackend {
        final AtomicBoolean generating = new AtomicBoolean(false);
        volatile boolean cancelled = false;
        private Thread worker;

        @Override public void loadModel(Path modelPath) throws AiServiceException { }
        @Override public void unloadModel() { }
        @Override public boolean isReady() { return true; }
        @Override public Optional<String> getModelName() { return Optional.of("hung"); }
        @Override public long getMemoryUsage() { return -1; }

        @Override
        public void chat(List<AiChatMessage> history, AiStreamCallback callback) throws AiServiceException {
            // chatWithoutTools delegates here; default impl would forward, but we override
            // chatWithoutTools directly below, so this path is not taken in the test.
            throw new UnsupportedOperationException();
        }

        @Override
        public void chat(List<AiChatMessage> history, float temperature, float topP, int maxTokens,
                         List<ChatFileContext.ActiveFileRef> activeFileRefs,
                         AiStreamCallback callback) throws AiServiceException {
            // Not exercised by the planner; chatWithoutTools is overridden directly below.
            throw new UnsupportedOperationException();
        }

        @Override
        public void chatWithoutTools(List<AiChatMessage> history, AiStreamCallback callback)
                throws AiServiceException {
            if (!generating.compareAndSet(false, true)) {
                throw new AiServiceException("Generation already in progress");
            }
            // Simulate a worker that blocks forever on a stream that never completes — the
            // exact scenario that leaked the generating flag before the fix.
            worker = Thread.ofVirtual().start(() -> {
                try {
                    new java.util.concurrent.CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    generating.set(false);
                }
            });
        }

        @Override
        public void chat(List<AiChatMessage> history, float temperature, float topP, int maxTokens,
                         AiStreamCallback callback) throws AiServiceException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancelGeneration() {
            cancelled = true;
            // Interrupt the stuck worker so its finally-block clears `generating`, mirroring
            // how the real backends' finally runs once dispose() terminates their stream.
            if (worker != null) worker.interrupt();
        }

        /**
         * Block until the cancelled worker has finished (its finally-block cleared the flag).
         * Cancellation is asynchronous — the planner calls cancelGeneration() which interrupts
         * the blocked worker, but the finally runs slightly later — so callers asserting
         * {@code !isGenerating()} must wait for the worker to actually exit first.
         */
        void awaitWorkerCleared() throws InterruptedException {
            Thread w = worker;
            if (w != null) w.join(5_000);
        }

        @Override public boolean isGenerating() { return generating.get(); }
    }
}
