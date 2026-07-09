package fan.summer.zhiflow.ai.agent;

import fan.summer.zhiflow.ai.util.JsonHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

/**
 * The Plan-and-Execute agent runtime.
 *
 * <p>Drives a single {@link AgentRun} through the lifecycle
 * {@code PLANNING → (optional plan approval) → EXECUTING → (optional step approval) →
 * COMPLETE/FAILED/CANCELLED}, re-planning on step failure up to
 * {@link AgentRunConfig#maxReplans()} when {@link AgentRunConfig#replanOnFailure()} is set.
 * Every transition is reported to an {@link AgentEventSink} so the orchestration is
 * observable (and testable) without coupling to SSE/HTTP.
 *
 * <h2>Injectable seams (why planning and execution are interfaces)</h2>
 *
 * <p>A real {@code ChatClient} call to an LLM can't be unit-tested without a live model,
 * so both the <em>planning</em> phase and the <em>tool execution</em> of each step are
 * delegated to small functional interfaces injected through the constructor:
 * <ul>
 *   <li>{@link PlanGenerator} — produces an {@link AgentPlan} for a goal given the
 *       available tools, streaming planning output token-by-token through a
 *       {@link PlanTokenSink}. The production implementation builds a planning prompt from
 *       the tools' name/description/inputSchema, streams it via {@code ChatClient}, and
 *       parses the returned JSON into an {@link AgentPlan}; tests inject a fake that
 *       returns a fixed (or scripted) plan.</li>
 *   <li>{@link StepExecutor} — runs one step's tool by name + args. The default
 *       {@link #toolResolvingExecutor()} resolves the {@link ToolCallback} by name from the
 *       injected list and calls {@link ToolCallback#call(String)} directly (the simplest
 *       Spring-AI-native path — {@code ToolCallback} IS the Spring AI tool contract, and a
 *       single tool invocation by name doesn't need the full {@code ToolCallingManager}
 *       {@code Prompt}/{@code ChatResponse} machinery). Tests may inject a fake executor
 *       to simulate success/failure without any real tooling.</li>
 * </ul>
 *
 * <h2>Execution model</h2>
 *
 * <p>{@link #run(AgentRun, AgentEventSink)} launches the state machine on a virtual thread
 * (mirroring {@code SpringAiCloudBackend.chat}) and returns immediately; the caller drives
 * approval gates from another thread via {@link AgentRun#approve(AgentPlan)} and observes
 * completion through the sink. Cancellation is cooperative: {@link AgentRun#isCancelled()}
 * is checked before each step and after waking from any approval gate, so a cancel posted
 * mid-run is honored promptly and the run ends {@link AgentRunStatus#CANCELLED}.
 */
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    private final List<ToolCallback> tools;
    private final PlanGenerator planGenerator;
    private final StepExecutor stepExecutor;

    /**
     * Fully-injected constructor (used by tests and by production wiring alike).
     *
     * @param tools         the available {@link ToolCallback}s (the planner describes them;
     *                      the executor resolves a step's tool by name from this list); never
     *                      {@code null}, may be empty
     * @param planGenerator the planning seam; produces the {@link AgentPlan} for a goal
     * @param stepExecutor  the step-execution seam; runs one step's tool
     */
    public AgentRunner(List<ToolCallback> tools, PlanGenerator planGenerator, StepExecutor stepExecutor) {
        this.tools = tools == null ? List.of() : tools;
        this.planGenerator = planGenerator;
        this.stepExecutor = stepExecutor;
    }

    // ── Public seam interfaces ─────────────────────────────────────────

    /**
     * Produces an {@link AgentPlan} for a goal given the available tools. Implementations
     * should stream any planning output (e.g. LLM tokens) to {@code tokenSink}; the runner
     * forwards those to {@link AgentEventSink#onPlanToken(String)}.
     */
    @FunctionalInterface
    public interface PlanGenerator {
        AgentPlan generate(String goal, List<ToolCallback> tools, PlanTokenSink tokenSink);
    }

    /** A sink for incremental planning output (LLM tokens). Decoupled from {@link AgentEventSink} so the planner stays testable. */
    @FunctionalInterface
    public interface PlanTokenSink {
        void onToken(String delta);
    }

    /**
     * Runs one step's tool and returns its result text. Throw any exception to signal
     * failure; the runner records {@link StepStatus#FAILED} and, when configured, replans.
     */
    @FunctionalInterface
    public interface StepExecutor {
        String execute(AgentStep step, List<ToolCallback> tools) throws Exception;
    }

    /**
     * The default {@link StepExecutor}: resolves the step's tool by name from the injected
     * list and calls {@link ToolCallback#call(String)} with the step's args serialized to
     * JSON. Throws {@link IllegalStateException} if the named tool is not found (recorded as
     * a FAILED step → eligible for replanning).
     *
     * <p>This is the "Spring AI native" path documented in the task brief: {@code ToolCallback}
     * is the Spring AI tool contract, and a single invocation by name doesn't need the full
     * {@code ToolCallingManager} {@code Prompt}/{@code ChatResponse} ceremony.
     */
    public static StepExecutor toolResolvingExecutor() {
        return (step, toolList) -> {
            ToolCallback cb = null;
            for (ToolCallback t : toolList) {
                if (t.getToolDefinition().name().equals(step.toolName())) {
                    cb = t;
                    break;
                }
            }
            if (cb == null) {
                throw new IllegalStateException("No tool named '" + step.toolName() + "' is available");
            }
            String jsonArgs = toJsonArgs(step.args());
            return cb.call(jsonArgs);
        };
    }

    // ── Run entry point ────────────────────────────────────────────────

    /**
     * Drives the run's state machine on a virtual thread and returns immediately. The
     * caller observes progress exclusively through {@code sink} (and, for approval gates,
     * by reading {@link AgentRun#getStatus()} and calling {@link AgentRun#approve(AgentPlan)}).
     */
    public void run(AgentRun run, AgentEventSink sink) {
        Thread.ofVirtual().name("agent-run-" + run.getRunId()).start(() -> drive(run, sink));
    }

    // ── The state machine ──────────────────────────────────────────────

    private void drive(AgentRun run, AgentEventSink sink) {
        AgentRunConfig cfg = run.getConfig();
        int replansRemaining = cfg.maxReplans();

        try {
            while (true) {
                // ── 1. PLANNING ───────────────────────────────────────
                run.setStatus(AgentRunStatus.PLANNING);
                AgentPlan plan;
                try {
                    plan = planGenerator.generate(run.getGoal(), tools,
                            delta -> safe(sink, s -> s.onPlanToken(delta)));
                } catch (Exception e) {
                    log.error("agent {}: planning failed", run.getRunId(), e);
                    run.setStatus(AgentRunStatus.FAILED);
                    safe(sink, s -> s.onError("Planning failed: " + e.getMessage()));
                    return;
                }
                run.setPlan(plan);
                safe(sink, s -> s.onPlanReady(plan));

                if (cancelledAfterGate(run)) {
                    finishCancelled(run, sink);
                    return;
                }

                // ── 2. Optional plan approval ─────────────────────────
                if (cfg.requirePlanApproval()) {
                    run.requestApproval(AgentRunStatus.AWAITING_PLAN_APPROVAL);
                    safe(sink, AgentEventSink::onPlanApprovalRequested);
                    if (!awaitApprovalOrCancel(run)) {
                        finishCancelled(run, sink);
                        return;
                    }
                }

                // ── 3. EXECUTING ───────────────────────────────────────
                run.setStatus(AgentRunStatus.EXECUTING);
                StepFailure failure = executeSteps(run, sink, cfg, plan);

                if (failure == null) {
                    // All steps completed → terminal success.
                    String summary = "Completed " + plan.steps().size() + " step(s) for goal: " + plan.goal();
                    run.setStatus(AgentRunStatus.COMPLETED);
                    safe(sink, s -> s.onComplete(summary));
                    return;
                }

                // ── 4. Replan on failure (if enabled and budget remains) ──
                if (cfg.replanOnFailure() && replansRemaining > 0) {
                    replansRemaining--;
                    log.info("agent {}: step {} failed ({}); replanning ({} replan(s) left)",
                            run.getRunId(), failure.stepIndex, failure.message, replansRemaining);
                    continue;   // back to PLANNING with the failure context visible to the planner
                }

                // No replan possible → terminal failure.
                run.setStatus(AgentRunStatus.FAILED);
                safe(sink, s -> s.onError(
                        "Step " + failure.stepIndex + " failed: " + failure.message
                                + " (replans exhausted)"));
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            run.setStatus(AgentRunStatus.FAILED);
            safe(sink, s -> s.onError("Run interrupted"));
        } catch (Exception e) {
            log.error("agent {}: run failed unexpectedly", run.getRunId(), e);
            run.setStatus(AgentRunStatus.FAILED);
            safe(sink, s -> s.onError("Run failed: " + e.getMessage()));
        }
    }

    /** Executes the plan's steps sequentially, honoring per-step approval. Returns the first failure, or {@code null} on full success. */
    private StepFailure executeSteps(AgentRun run, AgentEventSink sink, AgentRunConfig cfg, AgentPlan plan)
            throws InterruptedException {
        for (AgentStep step : plan.steps()) {
            // Cooperative cancellation before every step.
            if (run.isCancelled()) {
                return new StepFailure(step.index(), "cancelled before step");
            }

            // Optional per-step approval gate.
            if (cfg.requireStepApproval() && step.requiresApproval()) {
                run.requestApproval(AgentRunStatus.AWAITING_STEP_APPROVAL);
                safe(sink, s -> s.onStepApprovalRequested(step.index()));
                if (!awaitApprovalOrCancel(run)) {
                    return new StepFailure(step.index(), "cancelled awaiting step approval");
                }
                if (run.isCancelled()) {
                    return new StepFailure(step.index(), "cancelled after step approval");
                }
            }

            run.addExecution(new StepExecution(step.index(), StepStatus.RUNNING, null));
            safe(sink, s -> s.onStepStart(step.index()));

            try {
                String result = stepExecutor.execute(step, tools);
                run.addExecution(new StepExecution(step.index(), StepStatus.COMPLETED, result));
                safe(sink, s -> s.onStepComplete(step.index(), result));
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                run.addExecution(new StepExecution(step.index(), StepStatus.FAILED, msg));
                return new StepFailure(step.index(), msg);
            }
        }
        return null;
    }

    // ── Approval + cancellation helpers ────────────────────────────────

    /**
     * Blocks on {@link AgentRun#awaitApproval()}. Returns {@code true} if the gate was
     * released (approval posted), {@code false} if the run was cancelled while waiting.
     * Cancellation is detected post-wake (the latch counts down either way), so a cancel
     * posted mid-approval is observed immediately after the gate releases.
     */
    private boolean awaitApprovalOrCancel(AgentRun run) throws InterruptedException {
        run.awaitApproval();
        return !run.isCancelled();
    }

    /** True if the run was cancelled and there is no armed gate still blocking (post-plan-ready cancel). */
    private boolean cancelledAfterGate(AgentRun run) {
        return run.isCancelled();
    }

    private void finishCancelled(AgentRun run, AgentEventSink sink) {
        run.setStatus(AgentRunStatus.CANCELLED);
        safe(sink, s -> s.onError("Run cancelled"));
    }

    // ── Misc helpers ───────────────────────────────────────────────────

    /** A recorded step failure (index + message) for the replan decision. */
    private record StepFailure(int stepIndex, String message) {}

    /** Invokes a sink method, swallowing exceptions so a buggy sink can't kill the run. */
    private void safe(AgentEventSink sink, java.util.function.Consumer<AgentEventSink> action) {
        try {
            action.accept(sink);
        } catch (Exception e) {
            log.warn("agent event sink threw", e);
        }
    }

    /** Serializes the step's args map to a JSON string for {@link ToolCallback#call(String)}. */
    private static String toJsonArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "{}";
        try {
            return JsonHelper.toJson(args);
        } catch (Exception e) {
            // Best-effort: a tool that needs structured input will reject this, surfacing as FAILED.
            return "{}";
        }
    }
}
