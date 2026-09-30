package fan.summer.fengyu.ai.agent;

import fan.summer.fengyu.ai.service.AiModeService;
import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiServiceException;
import fan.summer.fengyu.ai.AiStreamCallback;
import fan.summer.fengyu.ai.ChatBackend;
import fan.summer.fengyu.ai.ChatFileContext;
import fan.summer.fengyu.ai.tools.ApprovalRequiredToolCallback;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

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
    void plannerPromptConstrainsToolsDependenciesAndApproval() {
        String prompt = ChatBackendPlanGenerator.SYSTEM_PROMPT;

        assertTrue(prompt.contains("using only the supplied tools"), prompt);
        assertTrue(prompt.contains("Treat GOAL, tool descriptions, schemas, effect metadata"), prompt);
        assertTrue(prompt.contains("only indexes of earlier prerequisite steps"), prompt);
        assertTrue(prompt.contains("read, write, command, or external"), prompt);
        assertTrue(prompt.contains("{{steps.<index>.result.<field>}}"), prompt);
        assertTrue(prompt.contains("return an empty steps array"), prompt);
    }

    @Test
    void toolCatalogIncludesHostEffectMetadataWhenAvailable() {
        ToolCallback command = new ApprovalRequiredToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder()
                    .name("run_command")
                    .description("Run a command")
                    .inputSchema("{\"type\":\"object\"}")
                    .build();

            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public String call(String input) { return "ok"; }
        };

        String catalog = ChatBackendPlanGenerator.toolCatalog(List.of(command));

        assertTrue(catalog.contains("\"effect\":\"command\""), catalog);
    }

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

    // ── Dynamic tool loading: two-phase planning ─────────────────────────────

    /** Responds synchronously from a script; records every system prompt it received. */
    static final class ScriptedBackend implements ChatBackend {
        final java.util.List<String> systemPrompts = new java.util.ArrayList<>();
        final java.util.List<String> userPrompts = new java.util.ArrayList<>();
        private final java.util.List<String> responses;
        private int call;

        ScriptedBackend(java.util.List<String> responses) { this.responses = responses; }

        @Override public void loadModel(Path modelPath) { }
        @Override public void unloadModel() { }
        @Override public boolean isReady() { return true; }
        @Override public Optional<String> getModelName() { return Optional.of("scripted"); }
        @Override public long getMemoryUsage() { return -1; }
        @Override public boolean isGenerating() { return false; }
        @Override public void cancelGeneration() { }

        @Override
        public void chatWithoutTools(java.util.List<AiChatMessage> history, AiStreamCallback callback) {
            systemPrompts.add(history.get(0).content());
            userPrompts.add(history.get(1).content());
            String response = responses.get(Math.min(call++, responses.size() - 1));
            callback.onToken(response);
            callback.onComplete(response, 1, 1.0);
        }

        @Override public void chat(java.util.List<AiChatMessage> history, AiStreamCallback callback) {
            throw new UnsupportedOperationException();
        }

        @Override public void chat(java.util.List<AiChatMessage> history, float temperature,
                float topP, int maxTokens, AiStreamCallback callback) {
            throw new UnsupportedOperationException();
        }

        @Override public void chat(java.util.List<AiChatMessage> history, float temperature,
                float topP, int maxTokens,
                java.util.List<fan.summer.fengyu.ai.ChatFileContext.ActiveFileRef> activeFileRefs,
                AiStreamCallback callback) {
            throw new UnsupportedOperationException();
        }
    }

    private static java.util.List<ToolCallback> manyTools(int count) {
        java.util.List<ToolCallback> tools = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            final int index = i;
            tools.add(new AgentRunnerTest.EchoToolCallback() {
                @Override public ToolDefinition getToolDefinition() {
                    return DefaultToolDefinition.builder()
                            .name("echo" + index).description("echo tool " + index)
                            .inputSchema("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}")
                            .build();
                }
            });
        }
        return tools;
    }

    private static final String PLAN_JSON = """
            {"goal":"echo","reasoning":"single step","steps":[
              {"index":0,"toolName":"echo5","args":{"text":"hi"},"description":"d","requiresApproval":false}]}
            """;

    private static final String ECHO_PLAN_JSON = """
            {"goal":"echo","reasoning":"single step","steps":[
              {"index":0,"toolName":"echo","args":{"text":"hi"},"description":"d","requiresApproval":false}]}
            """;

    @Test
    void smallCatalogUsesTheSinglePhaseCallUnchanged() {
        ScriptedBackend backend = new ScriptedBackend(java.util.List.of(ECHO_PLAN_JSON));
        AiModeService modeService = new AiModeService();
        modeService.setService(backend);
        ChatBackendPlanGenerator generator = new ChatBackendPlanGenerator(modeService, 5);

        AgentPlan plan = generator.generate("echo", java.util.List.of(new AgentRunnerTest.EchoToolCallback()), null);

        assertEquals(1, plan.steps().size());
        assertEquals(1, backend.systemPrompts.size(), "small catalog must not plan in two phases");
        assertTrue(backend.systemPrompts.get(0).contains("You are Infinia's workflow planner."));
        assertFalse(backend.systemPrompts.get(0).contains("first stage"));
    }

    @Test
    void largeCatalogSelectsToolsFirstThenPlansAgainstTheSelection() {
        String selection = """
                {"selectedTools":["echo5"],"reasoning":"only echo5 is needed"}
                """;
        ScriptedBackend backend = new ScriptedBackend(java.util.List.of(selection, PLAN_JSON));
        AiModeService modeService = new AiModeService();
        modeService.setService(backend);
        ChatBackendPlanGenerator generator = new ChatBackendPlanGenerator(modeService, 10);

        AgentPlan plan = generator.generate("echo", manyTools(26), null);

        assertEquals(1, plan.steps().size());
        assertEquals("echo5", plan.steps().get(0).toolName());
        assertEquals(2, backend.systemPrompts.size(), "expected a selection call and a refine call");
        assertTrue(backend.systemPrompts.get(0).contains("first stage"));
        // The selection catalog carries no input schemas — that is the whole point.
        assertFalse(backend.userPrompts.get(0).contains("inputSchema"), backend.userPrompts.get(0));
        // The refine call narrows the catalog to the selection and keeps every schema.
        assertTrue(backend.systemPrompts.get(1).contains("narrowed by an earlier selection"));
        assertTrue(backend.userPrompts.get(1).contains("echo5"));
        assertFalse(backend.userPrompts.get(1).contains("echo25"), backend.userPrompts.get(1));
    }

    @Test
    void selectionPromptConstrainsToCatalogAndUntrustedData() {
        String prompt = ChatBackendPlanGenerator.SELECT_SYSTEM_PROMPT;
        assertTrue(prompt.contains("Never invent a tool"), prompt);
        assertTrue(prompt.contains("untrusted data"), prompt);
        assertTrue(prompt.contains("selectedTools"), prompt);
    }

    @Test
    void malformedRefinementFallsBackToTheFullSchemaCall() {
        String selection = """
                {"selectedTools":["echo5"],"reasoning":"ok"}
                """;
        ScriptedBackend backend = new ScriptedBackend(
                java.util.List.of(selection, "not json at all", PLAN_JSON));
        AiModeService modeService = new AiModeService();
        modeService.setService(backend);
        ChatBackendPlanGenerator generator = new ChatBackendPlanGenerator(modeService, 10);

        AgentPlan plan = generator.generate("echo", manyTools(26), null);

        assertEquals(1, plan.steps().size(), "fallback single-phase call must still produce a plan");
        assertEquals(3, backend.systemPrompts.size());
        assertTrue(backend.systemPrompts.get(2).contains("You are Infinia's workflow planner."));
        assertFalse(backend.systemPrompts.get(2).contains("narrowed by an earlier selection"));
        // The fallback catalog is the FULL tool list again.
        assertTrue(backend.userPrompts.get(2).contains("echo25"));
    }
}
