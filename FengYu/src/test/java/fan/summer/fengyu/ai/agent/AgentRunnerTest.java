package fan.summer.fengyu.ai.agent;

import fan.summer.fengyu.ai.tools.ApprovalRequiredToolCallback;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgentRunner} that prove the Plan-and-Execute orchestration is
 * correct <b>without</b> a Spring context and <b>without</b> a live LLM.
 *
 * <p>The two injectable seams — {@link AgentRunner.PlanGenerator} (planning) and
 * {@link AgentRunner.StepExecutor} (tool execution) — are satisfied by hand-rolled fakes.
 * {@link AgentRunner} runs its state machine on a virtual thread; the tests latch on
 * {@code onComplete}/{@code onError} (fired exactly once at the terminal state) and then
 * assert against a recorded event list.
 *
 * <p>Three scenarios are covered:
 * <ol>
 *   <li><b>Happy path</b> — no approval, one step calling a mock tool that succeeds:
 *       verifies the ordered event stream
 *       {@code onPlanReady → onStepStart(0) → onStepComplete(0,...) → onComplete}.</li>
 *   <li><b>Replan on failure</b> — the first plan's tool fails, {@code maxReplans=1}:
 *       the runner replans and the second plan succeeds → {@code onComplete}.</li>
 *   <li><b>Replans exhausted</b> — the tool always fails, {@code maxReplans=1}:
 *       after one replan the runner gives up → {@code onError}.</li>
 * </ol>
 */
class AgentRunnerTest {

    /** A real Spring AI {@link ToolCallback} that echoes its raw JSON input. */
    static class EchoToolCallback implements ToolCallback {
        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name("echo")
                    .description("echoes the provided text back")
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}")
                    .build();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return ToolMetadata.builder().returnDirect(false).build();
        }

        @Override
        public String call(String toolInput) {
            return "echo:" + toolInput;
        }
    }

    static final class ApprovalRequiredEchoToolCallback
            extends EchoToolCallback implements ApprovalRequiredToolCallback {
    }

    /** Records every {@link AgentEventSink} call in arrival order for sequence assertions. */
    static final class RecordingSink implements AgentEventSink {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch done = new CountDownLatch(1);

        @Override public void onPlanToken(String delta) { events.add("onPlanToken:" + delta); }
        @Override public void onPlanReady(AgentPlan plan) { events.add("onPlanReady:" + plan.goal()); }
        @Override public void onPlanApprovalRequested() { events.add("onPlanApprovalRequested"); }
        @Override public void onStepStart(int index) { events.add("onStepStart:" + index); }
        @Override public void onStepComplete(int index, String result) { events.add("onStepComplete:" + index); }
        @Override public void onStepApprovalRequested(int index) { events.add("onStepApprovalRequested:" + index); }
        @Override public void onComplete(String summary) { events.add("onComplete"); done.countDown(); }
        @Override public void onError(String message) { events.add("onError:" + message); done.countDown(); }

        boolean awaitDone() throws InterruptedException { return done.await(5, TimeUnit.SECONDS); }
    }

    private static AgentStep step(int index, String toolName, Map<String, Object> args) {
        return new AgentStep(index, toolName, args, "step " + index, false);
    }

    private static AgentRun runFor(String goal, AgentRunConfig config) {
        return new AgentRun("run-1", goal, config);
    }

    // ── 1. Happy path: one mock tool succeeds, no approval ──────────────

    @Test
    void happyPath_noApproval_oneStepSucceeds() throws Exception {
        List<ToolCallback> tools = List.of(new EchoToolCallback());
        RecordingSink sink = new RecordingSink();

        AgentPlan plan = new AgentPlan(
                "echo hi", List.of(step(0, "echo", Map.of("text", "hi"))), "single echo");
        // Fake planner always returns the same plan.
        AgentRunner.PlanGenerator planner = (goal, tks, tokenSink) -> plan;
        // Real-ish executor: resolve by name from the injected tools and call it.
        AgentRunner.StepExecutor executor = AgentRunner.toolResolvingExecutor();

        AgentRun run = runFor("echo hi", new AgentRunConfig(false, false, false, 0));
        AgentRunner runner = new AgentRunner(tools, planner, executor);

        runner.run(run, sink);
        assertTrue(sink.awaitDone(), "onComplete should fire within timeout");

        // Ordered event stream: plan ready → step start → step complete → complete.
        // Assert both PRESENCE and the SEQUENCE (the contract the runner must honor).
        assertTrue(sink.events.contains("onPlanReady:echo hi"), "onPlanReady should fire: " + sink.events);
        assertTrue(sink.events.contains("onStepStart:0"), "onStepStart(0) should fire: " + sink.events);
        assertTrue(sink.events.contains("onStepComplete:0"), "onStepComplete(0) should fire: " + sink.events);
        assertTrue(sink.events.contains("onComplete"), "onComplete should fire: " + sink.events);
        assertFalse(sink.events.contains("onError:null"), "no onError in happy path");

        // Lock in the ORDER: onPlanReady → onStepStart(0) → onStepComplete(0) → onComplete.
        int idxPlanReady = sink.events.indexOf("onPlanReady:echo hi");
        int idxStepStart = sink.events.indexOf("onStepStart:0");
        int idxStepComplete = sink.events.indexOf("onStepComplete:0");
        int idxComplete = sink.events.indexOf("onComplete");
        assertTrue(idxPlanReady < idxStepStart,
                "onPlanReady must precede onStepStart(0): " + sink.events);
        assertTrue(idxStepStart < idxStepComplete,
                "onStepStart(0) must precede onStepComplete(0): " + sink.events);
        assertTrue(idxStepComplete < idxComplete,
                "onStepComplete(0) must precede onComplete: " + sink.events);

        // The step actually ran the tool (the executor resolved "echo" and called it).
        List<StepExecution> execs = run.getExecutions();
        assertFalse(execs.isEmpty(), "an execution should be recorded");
        assertEquals(StepStatus.COMPLETED, execs.get(execs.size() - 1).status());

        assertEquals(AgentRunStatus.COMPLETED, run.getStatus());
        assertNotNull(run.getPlan(), "plan should be set on the run");
    }

    // ── 2. Replan on failure: first plan fails, second succeeds ─────────

    @Test
    void replanOnFailure_secondPlanSucceeds() throws Exception {
        List<ToolCallback> tools = List.of(new EchoToolCallback());
        RecordingSink sink = new RecordingSink();

        AgentPlan failing = new AgentPlan(
                "goal", List.of(step(0, "echo", Map.of("text", "first attempt"))), "will fail");
        AgentPlan good = new AgentPlan(
                "goal", List.of(step(0, "echo", Map.of("text", "ok"))), "fixed");
        // Planner returns the failing plan first, then the good plan.
        AtomicInteger plannerCalls = new AtomicInteger();
        AgentRunner.PlanGenerator planner = (goal, tks, tokenSink) ->
                plannerCalls.getAndIncrement() == 0 ? failing : good;

        AtomicInteger executionCalls = new AtomicInteger();
        // First execution fails; the same valid tool succeeds after replanning.
        AgentRunner.StepExecutor executor = (step1, tks) -> {
            if (executionCalls.getAndIncrement() == 0) {
                throw new RuntimeException("tool exploded");
            }
            return AgentRunner.toolResolvingExecutor().execute(step1, tks);
        };

        AgentRun run = runFor("goal", new AgentRunConfig(false, false, true, 1));
        AgentRunner runner = new AgentRunner(tools, planner, executor);

        runner.run(run, sink);
        assertTrue(sink.awaitDone(), "terminal event should fire within timeout");

        // The runner replanned once (maxReplans=1) and then completed.
        assertEquals(2, plannerCalls.get(), "planner should be called twice (initial + 1 replan): " + plannerCalls.get());
        assertEquals(AgentRunStatus.COMPLETED, run.getStatus());
        assertTrue(sink.events.contains("onComplete"), "onComplete should fire after replan: " + sink.events);
        assertFalse(sink.events.stream().anyMatch(e -> e.startsWith("onError")), "no onError: " + sink.events);
        // The failing step's failure was recorded as a FAILED execution before the replan.
        assertTrue(run.getExecutions().stream().anyMatch(e -> e.status() == StepStatus.FAILED),
                "the failed step should be recorded: " + run.getExecutions());
    }

    @Test
    void replanOnFailure_includesFailureContextInNextPlanningRequest() throws Exception {
        List<ToolCallback> tools = List.of(new EchoToolCallback());
        RecordingSink sink = new RecordingSink();
        AgentPlan plan = new AgentPlan(
                "goal", List.of(step(0, "echo", Map.of("text", "attempt"))), "try");
        List<String> planningGoals = new ArrayList<>();
        AgentRunner.PlanGenerator planner = (goal, tks, tokenSink) -> {
            planningGoals.add(goal);
            return plan;
        };
        AtomicInteger executionCalls = new AtomicInteger();
        AgentRunner.StepExecutor executor = (plannedStep, tks) -> {
            if (executionCalls.getAndIncrement() == 0) {
                throw new RuntimeException("tool exploded");
            }
            return "ok";
        };

        AgentRun run = runFor("goal", new AgentRunConfig(false, false, true, 1));
        new AgentRunner(tools, planner, executor).run(run, sink);

        assertTrue(sink.awaitDone());
        assertEquals(2, planningGoals.size());
        assertEquals("goal", planningGoals.getFirst());
        assertTrue(planningGoals.get(1).contains("tool exploded"), planningGoals.get(1));
        assertTrue(planningGoals.get(1).contains("step 0"), planningGoals.get(1));
    }

    // ── 3. Replans exhausted: tool always fails, maxReplans=1 → onError ─

    @Test
    void replansExhausted_emitsOnError() throws Exception {
        List<ToolCallback> tools = List.of(new EchoToolCallback());
        RecordingSink sink = new RecordingSink();

        // Plan that always asks for the failing tool.
        AgentPlan failing = new AgentPlan(
                "goal", List.of(step(0, "echo", Map.of("text", "fail"))), "will fail");
        AtomicInteger plannerCalls = new AtomicInteger();
        AgentRunner.PlanGenerator planner = (goal, tks, tokenSink) -> {
            plannerCalls.incrementAndGet();
            return failing;
        };

        AgentRunner.StepExecutor executor = (step1, tks) -> {
            throw new RuntimeException("tool exploded");
        };

        AgentRun run = runFor("goal", new AgentRunConfig(false, false, true, 1));
        AgentRunner runner = new AgentRunner(tools, planner, executor);

        runner.run(run, sink);
        assertTrue(sink.awaitDone(), "terminal event should fire within timeout");

        // Initial plan + 1 replan = 2 planner calls; then it gives up.
        assertEquals(2, plannerCalls.get(),
                "planner should be called twice (initial + 1 replan) then give up: " + plannerCalls.get());
        assertEquals(AgentRunStatus.FAILED, run.getStatus());
        assertTrue(sink.events.stream().anyMatch(e -> e.startsWith("onError")),
                "onError should fire when replans exhausted: " + sink.events);
        assertFalse(sink.events.contains("onComplete"), "no onComplete on failure");
    }

    // ── 4. Plan approval gate: blocks until approve() releases it ───────

    @Test
    void planApproval_blocksUntilApproved() throws Exception {
        List<ToolCallback> tools = List.of(new EchoToolCallback());
        RecordingSink sink = new RecordingSink();

        AgentPlan plan = new AgentPlan(
                "echo hi", List.of(step(0, "echo", Map.of("text", "hi"))), "single echo");
        AgentRunner.PlanGenerator planner = (goal, tks, tokenSink) -> plan;
        AgentRunner.StepExecutor executor = AgentRunner.toolResolvingExecutor();

        AgentRun run = runFor("echo hi", new AgentRunConfig(true, false, false, 0));
        AgentRunner runner = new AgentRunner(tools, planner, executor);

        runner.run(run, sink);

        // The runner should reach AWAITING_PLAN_APPROVAL and block there. Give it a moment,
        // confirm it is waiting, then approve.
        Thread.sleep(200);
        assertEquals(AgentRunStatus.AWAITING_PLAN_APPROVAL, run.getStatus(),
                "runner should be paused awaiting plan approval");
        assertTrue(sink.events.contains("onPlanApprovalRequested"),
                "onPlanApprovalRequested should fire: " + sink.events);

        // Now release the gate from the "controller" thread.
        run.approve(plan);

        assertTrue(sink.awaitDone(), "onComplete should fire after approval");
        assertEquals(AgentRunStatus.COMPLETED, run.getStatus());
    }

    @Test
    void approvalRequiredTool_blocksEvenWhenStepApprovalIsDisabled() throws Exception {
        List<ToolCallback> tools = List.of(new ApprovalRequiredEchoToolCallback());
        RecordingSink sink = new RecordingSink();
        AgentPlan plan = new AgentPlan(
                "echo hi", List.of(step(0, "echo", Map.of("text", "hi"))), "sensitive echo");
        AgentRun run = runFor("echo hi", new AgentRunConfig(false, false, false, 0));
        AgentRunner runner = new AgentRunner(
                tools, (goal, tks, tokenSink) -> plan, AgentRunner.toolResolvingExecutor());

        runner.run(run, sink);

        Thread.sleep(200);
        assertEquals(AgentRunStatus.AWAITING_STEP_APPROVAL, run.getStatus());
        assertTrue(sink.events.contains("onStepApprovalRequested:0"));
        assertTrue(run.getExecutions().isEmpty(), "tool must not execute before approval");

        run.approve(null);

        assertTrue(sink.awaitDone(), "onComplete should fire after sensitive-tool approval");
        assertEquals(AgentRunStatus.COMPLETED, run.getStatus());
    }

    // ── 5. Cancellation before a step → run ends CANCELLED, no execution ─

    @Test
    void cancelledBeforeStep_endsCancelled() throws Exception {
        List<ToolCallback> tools = List.of(new EchoToolCallback());
        RecordingSink sink = new RecordingSink();

        AgentPlan plan = new AgentPlan(
                "echo hi", List.of(step(0, "echo", Map.of("text", "hi"))), "single echo");
        AgentRunner.PlanGenerator planner = (goal, tks, tokenSink) -> plan;
        // Executor that asserts it should NEVER run.
        AgentRunner.StepExecutor executor = (s, tks) -> { fail("executor should not run when cancelled"); return null; };

        AgentRun run = runFor("echo hi", new AgentRunConfig(true, false, false, 0));
        AgentRunner runner = new AgentRunner(tools, planner, executor);

        runner.run(run, sink);

        // Wait for the runner to reach the plan-approval gate, then cancel.
        Thread.sleep(200);
        run.markCancelled();
        run.approve(null);   // release the gate so the runner wakes and observes cancellation

        assertTrue(sink.awaitDone(), "a terminal event should fire after cancel");
        assertEquals(AgentRunStatus.CANCELLED, run.getStatus(),
                "status should be CANCELLED: " + run.getStatus());
        assertTrue(run.getExecutions().isEmpty(), "no step should execute on cancel");
    }

    @Test
    void suppliedWorkflow_skipsPlannerAndInjectsPreviousResult() throws Exception {
        RecordingSink sink = new RecordingSink();
        AgentPlan workflow = new AgentPlan("chain", List.of(
                step(0, "echo", Map.of("text", "first")),
                step(1, "echo", Map.of("text", "{{steps.0.result}}"))
        ), "caller supplied");
        AgentRun run = runFor("chain", new AgentRunConfig(false, false, false, 0));
        run.setPlan(workflow);

        AtomicInteger plannerCalls = new AtomicInteger();
        List<String> receivedInputs = new ArrayList<>();
        AgentRunner runner = new AgentRunner(List.of(new EchoToolCallback()),
                (goal, tools, tokenSink) -> {
                    plannerCalls.incrementAndGet();
                    return workflow;
                },
                (plannedStep, tools) -> {
                    receivedInputs.add(String.valueOf(plannedStep.args().get("text")));
                    return plannedStep.index() == 0 ? "{\"value\":\"from-first\"}" : "done";
                });

        runner.run(run, sink);
        assertTrue(sink.awaitDone());

        assertEquals(0, plannerCalls.get(), "a supplied workflow must bypass AI planning");
        assertEquals(List.of("first", "{value=from-first}"), receivedInputs);
        assertEquals(AgentRunStatus.COMPLETED, run.getStatus());
    }

    @Test
    void dependencyReadyStepsRunConcurrentlyAndJoinBeforeDependentStep() throws Exception {
        RecordingSink sink = new RecordingSink();
        AgentPlan workflow = new AgentPlan("parallel", List.of(
                new AgentStep(0, "echo", Map.of("text", "left"), "left", false, List.of()),
                new AgentStep(1, "echo", Map.of("text", "right"), "right", false, List.of()),
                new AgentStep(2, "echo",
                        Map.of("text", "{{steps.0.result}} + {{steps.1.result}}"),
                        "join", false, List.of(0, 1))
        ), "parallel branches");
        AgentRun run = runFor("parallel", new AgentRunConfig(false, false, false, 0));
        run.setPlan(workflow);
        CountDownLatch branchesStarted = new CountDownLatch(2);
        AtomicInteger branchesCompleted = new AtomicInteger();
        List<String> joinInputs = Collections.synchronizedList(new ArrayList<>());

        AgentRunner runner = new AgentRunner(List.of(new EchoToolCallback()),
                (goal, tools, tokenSink) -> workflow,
                (plannedStep, tools) -> {
                    if (plannedStep.index() < 2) {
                        branchesStarted.countDown();
                        assertTrue(branchesStarted.await(2, TimeUnit.SECONDS),
                                "both independent branches must start before either finishes");
                        branchesCompleted.incrementAndGet();
                        return plannedStep.index() == 0 ? "left-result" : "right-result";
                    }
                    assertEquals(2, branchesCompleted.get(),
                            "dependent step must wait for both prerequisites");
                    joinInputs.add(String.valueOf(plannedStep.args().get("text")));
                    return "joined";
                });

        runner.run(run, sink);
        assertTrue(sink.awaitDone());
        assertEquals(AgentRunStatus.COMPLETED, run.getStatus());
        assertEquals(List.of("left-result + right-result"), joinInputs);
    }

    @Test
    void editedApprovedWorkflowIsTheOneExecuted() throws Exception {
        RecordingSink sink = new RecordingSink();
        AgentPlan original = new AgentPlan("goal",
                List.of(step(0, "echo", Map.of("text", "original"))), "original");
        AgentPlan edited = new AgentPlan("goal",
                List.of(step(0, "echo", Map.of("text", "edited"))), "edited");
        AgentRun run = runFor("goal", new AgentRunConfig(true, false, false, 0));
        List<String> inputs = new ArrayList<>();
        AgentRunner runner = new AgentRunner(List.of(new EchoToolCallback()),
                (goal, tools, tokenSink) -> original,
                (plannedStep, tools) -> {
                    inputs.add(String.valueOf(plannedStep.args().get("text")));
                    return "ok";
                });

        runner.run(run, sink);
        Thread.sleep(200);
        run.approve(edited);
        assertTrue(sink.awaitDone());

        assertEquals(List.of("edited"), inputs);
    }

    @Test
    void resumedWorkflowSkipsPersistedCompletedStepsAndReusesTheirResults() throws Exception {
        RecordingSink sink = new RecordingSink();
        AgentPlan workflow = new AgentPlan("resume", List.of(
                step(0, "echo", Map.of("text", "already done")),
                step(1, "echo", Map.of("text", "{{steps.0.result}}"))
        ), "resume");
        AgentRun run = runFor("resume", new AgentRunConfig(false, false, false, 0));
        run.setPlan(workflow);
        run.restoreExecutions(List.of(
                new StepExecution(0, StepStatus.COMPLETED, "persisted-result")));
        List<Integer> executed = new ArrayList<>();
        List<String> inputs = new ArrayList<>();
        AgentRunner runner = new AgentRunner(List.of(new EchoToolCallback()),
                (goal, tools, tokenSink) -> workflow,
                (step, tools) -> {
                    executed.add(step.index());
                    inputs.add(String.valueOf(step.args().get("text")));
                    return "done";
                });

        runner.run(run, sink);

        assertTrue(sink.awaitDone());
        assertEquals(List.of(1), executed);
        assertEquals(List.of("persisted-result"), inputs);
        assertEquals(AgentRunStatus.COMPLETED, run.getStatus());
    }
}
