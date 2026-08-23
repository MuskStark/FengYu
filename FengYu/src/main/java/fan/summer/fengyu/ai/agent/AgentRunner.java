package fan.summer.fengyu.ai.agent;

import fan.summer.fengyu.ai.util.JsonHelper;
import fan.summer.fengyu.ai.tools.ToolApprovalPolicy;
import fan.summer.fengyu.ai.tools.ToolGuardService;
import fan.summer.fengyu.ai.tools.ToolResultStatus;
import fan.summer.fengyu.ai.tools.AiPermissionContext;
import fan.summer.fengyu.ai.tools.AiRunContext;
import fan.summer.fengyu.ai.tools.ToolEffect;
import fan.summer.fengyu.ai.tools.AiPermissionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Supplier;

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
    // Path segments after .result accept dotted keys and [N] array indexes:
    // {{steps.0.result.files[2].name}} → group(2) = ".files[2].name".
    private static final Pattern STEP_RESULT =
            Pattern.compile("\\{\\{steps\\.(\\d+)\\.result((?:\\.[A-Za-z0-9_-]+|\\[\\d+])*)}}");
    private static final String LAST_RESULT = "{{last.result}}";

    private final Supplier<List<ToolCallback>> toolProvider;
    private final PlanGenerator planGenerator;
    private final StepExecutor stepExecutor;
    /** Optional layered guard (PreToolUse hooks + permission rules); null keeps legacy policy. */
    private final ToolGuardService guard;
    /** Optional usage metrics; null in tests keeps the runner fully side-effect free. */
    private final fan.summer.fengyu.ai.metrics.AiUsageMetrics metrics;

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
        this(() -> tools == null ? List.of() : tools, planGenerator, stepExecutor, null, null);
    }

    /** Production constructor for a tool catalog that can change between agent runs. */
    public AgentRunner(Supplier<List<ToolCallback>> toolProvider, PlanGenerator planGenerator,
                       StepExecutor stepExecutor) {
        this(toolProvider, planGenerator, stepExecutor, null, null);
    }

    /** Production constructor — the guard layers hooks + permission rules over the mode default. */
    public AgentRunner(Supplier<List<ToolCallback>> toolProvider, PlanGenerator planGenerator,
                       StepExecutor stepExecutor, ToolGuardService guard) {
        this(toolProvider, planGenerator, stepExecutor, guard, null);
    }

    /** Widest constructor — guard plus usage metrics. */
    public AgentRunner(Supplier<List<ToolCallback>> toolProvider, PlanGenerator planGenerator,
                       StepExecutor stepExecutor, ToolGuardService guard,
                       fan.summer.fengyu.ai.metrics.AiUsageMetrics metrics) {
        this.toolProvider = toolProvider == null ? List::of : toolProvider;
        this.planGenerator = planGenerator;
        this.stepExecutor = stepExecutor;
        this.guard = guard;
        this.metrics = metrics;
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
            return ToolResultStatus.requireSuccess(cb.call(jsonArgs));
        };
    }

    // ── Run entry point ────────────────────────────────────────────────

    /**
     * Drives the run's state machine on a virtual thread and returns immediately. The
     * caller observes progress exclusively through {@code sink} (and, for approval gates,
     * by reading {@link AgentRun#getStatus()} and calling {@link AgentRun#approve(AgentPlan)}).
     */
    public void run(AgentRun run, AgentEventSink sink) {
        Thread.ofVirtual().name("agent-run-" + run.getRunId()).start(() -> {
            Thread current = Thread.currentThread();
            run.attachRunnerThread(current);
            try { drive(run, sink); }
            finally { run.detachRunnerThread(current); }
        });
    }

    // ── The state machine ──────────────────────────────────────────────

    private void drive(AgentRun run, AgentEventSink sink) {
        final java.util.concurrent.atomic.AtomicBoolean metricsClosed =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            driveGuarded(run, sink, metricsClosed);
        } finally {
            // Every terminal transition funnels through finishXxx helpers that close the
            // metrics EXACTLY once; abnormal exits (interrupt/unexpected) still record a
            // terminal status here so no run leaks its started-state (P2-5).
            if (metrics != null && metricsClosed.compareAndSet(false, true)) {
                recordRunMetrics(run.getRunId(),
                        run.getStatus() == AgentRunStatus.CANCELLED ? "cancelled" : "failed");
            }
        }
    }

    private void driveGuarded(AgentRun run, AgentEventSink sink,
                              java.util.concurrent.atomic.AtomicBoolean metricsClosed) {
        // One consistent catalog per run. Plugin callbacks re-check enabled/installed state at call
        // time, so disabling a plugin also safely stops a later step in an already-running plan.
        List<ToolCallback> tools = List.copyOf(toolProvider.get());
        if (metrics != null) metrics.runStarted(run.getRunId());
        AgentRunConfig cfg = run.getConfig();
        int replansRemaining = cfg.maxReplans();
        AgentPlan suppliedWorkflow = run.getPlan();
        String planningGoal = run.getGoal();

        try {
            while (true) {
                // ── 1. PLANNING ───────────────────────────────────────
                run.setStatus(AgentRunStatus.PLANNING);
                AgentPlan plan;
                try {
                    if (suppliedWorkflow != null) {
                        plan = suppliedWorkflow;
                        suppliedWorkflow = null;
                    } else {
                        plan = planGenerator.generate(planningGoal, tools,
                                delta -> safe(sink, s -> s.onPlanToken(delta)));
                    }
                    validatePlan(plan, tools, cfg.isReadOnly());
                } catch (Exception e) {
                    log.error("agent {}: planning failed", run.getRunId(), e);
                    run.setStatus(AgentRunStatus.FAILED);
                    safe(sink, s -> s.onError("Planning failed: " + e.getMessage()));
                    return;
                }
                run.setPlan(plan);
                AgentPlan readyPlan = plan;
                safe(sink, s -> s.onPlanReady(readyPlan));

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
                // Approval may have supplied an edited workflow. Always execute the current
                // run plan rather than the stale pre-approval local variable.
                plan = run.getPlan();
                try {
                    validatePlan(plan, tools, cfg.isReadOnly());
                } catch (Exception e) {
                    run.setStatus(AgentRunStatus.FAILED);
                    safe(sink, s -> s.onError("Invalid workflow: " + e.getMessage()));
                    return;
                }
                run.setStatus(AgentRunStatus.EXECUTING);
                StepFailure failure = executeSteps(run, sink, cfg, plan, tools);

                if (failure == null) {
                    // All steps completed → terminal success.
                    String summary = "Completed " + plan.steps().size() + " step(s) for goal: " + plan.goal();
                    run.setStatus(AgentRunStatus.COMPLETED);
                    closeRunMetrics(metricsClosed, run.getRunId(), "completed");
                    safe(sink, s -> s.onComplete(summary));
                    final String goalAtCompletion = plan.goal();
                    fireGuard(() -> guard.observeRunComplete(run.getRunId(), goalAtCompletion, summary, false));
                    return;
                }
                if (run.isCancelled()) {
                    finishCancelled(run, sink);
                    return;
                }

                // ── 4. Replan on failure (if enabled and budget remains) ──
                if (cfg.replanOnFailure() && replansRemaining > 0) {
                    replansRemaining--;
                    planningGoal = replanGoal(run.getGoal(), failure, run.getExecutions());
                    log.info("agent {}: step {} failed ({}); replanning ({} replan(s) left)",
                            run.getRunId(), failure.stepIndex, failure.message, replansRemaining);
                    continue;
                }

                // No replan possible → terminal failure.
                run.setStatus(AgentRunStatus.FAILED);
                closeRunMetrics(metricsClosed, run.getRunId(), "failed");
                String failureMessage = "Step " + failure.stepIndex + " failed: " + failure.message
                        + " (replans exhausted)";
                safe(sink, s -> s.onError(failureMessage));
                final String goalAtFailure = plan.goal();
                fireGuard(() -> guard.observeRunComplete(run.getRunId(), goalAtFailure,
                        failureMessage, true));
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (run.isCancelled()) finishCancelled(run, sink);
            else {
                run.setStatus(AgentRunStatus.FAILED);
                safe(sink, s -> s.onError("Run interrupted"));
            }
        } catch (Exception e) {
            log.error("agent {}: run failed unexpectedly", run.getRunId(), e);
            run.setStatus(AgentRunStatus.FAILED);
            safe(sink, s -> s.onError("Run failed: " + e.getMessage()));
        }
    }

    /**
     * Executes dependency-ready DAG levels concurrently on virtual threads. Approval gates are
     * resolved before a level starts, so no worker can leave a sibling blocked behind a shared
     * run-level approval latch.
     */
    private StepFailure executeSteps(AgentRun run, AgentEventSink sink, AgentRunConfig cfg,
                                     AgentPlan plan, List<ToolCallback> tools)
            throws InterruptedException {
        Map<Integer, String> results = Collections.synchronizedMap(new HashMap<>());
        Set<Integer> completed = new HashSet<>();
        for (StepExecution execution : run.getRestoredExecutions()) {
            completed.add(execution.index());
            results.put(execution.index(), execution.result());
        }
        // Steps omitted by control flow. A skipped step satisfies dependencies (its
        // downstream becomes ready — and typically cascades to skipped itself) but
        // contributes no result: referencing it fails resolution like a missing output.
        Set<Integer> skipped = new HashSet<>();

        Map<Integer, AgentStep> pending = new LinkedHashMap<>();
        for (AgentStep step : plan.steps()) {
            if (!completed.contains(step.index())) pending.put(step.index(), step);
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            while (!pending.isEmpty()) {
                List<AgentStep> ready = pending.values().stream()
                        .filter(step -> completed.containsAll(dependencies(step)))
                        .sorted(Comparator.comparingInt(AgentStep::index))
                        .toList();
                if (ready.isEmpty()) {
                    return new StepFailure(pending.keySet().iterator().next(),
                            "workflow dependencies cannot be satisfied");
                }

                if (run.isCancelled()) {
                    return new StepFailure(ready.getFirst().index(), "cancelled before step");
                }

                // Control flow: resolve branch conditions before anything else touches the
                // step. A step whose runWhen is unsatisfied (or whose dependencies were all
                // skipped) is recorded SKIPPED and settles immediately — no guard, approval,
                // or tool call. The loop then recomputes readiness so skipped steps unblock
                // their downstream.
                List<AgentStep> toSkip = ready.stream()
                        .filter(step -> shouldSkip(step, results, skipped))
                        .toList();
                if (!toSkip.isEmpty()) {
                    for (AgentStep step : toSkip) {
                        pending.remove(step.index());
                        completed.add(step.index());
                        skipped.add(step.index());
                        run.addExecution(new StepExecution(step.index(), StepStatus.SKIPPED, null));
                    }
                    for (AgentStep step : toSkip) {
                        safe(sink, s -> s.onStepSkipped(step.index()));
                    }
                    continue;
                }

                // Resolve {{steps.N.result}}/{{last.result}} ONCE, before any guard or
                // approval decision: the guard, the legacy approval policy, the executor,
                // and the PostToolUse audit hooks must all see the SAME effective
                // arguments. Checking templates ("{{steps.0.result}}") would let a
                // previous step's output smuggle a denied command past the rules.
                Map<Integer, AgentStep> effectiveSteps = new LinkedHashMap<>();
                for (AgentStep step : ready) {
                    effectiveSteps.put(step.index(), new AgentStep(step.index(), step.toolName(),
                            resolveArgs(step.args(), results, results.get(step.index() - 1)),
                            step.description(), step.requiresApproval(), step.dependsOn(),
                            step.pinnedResult(), step.runWhen(), step.retryPolicy(),
                            step.outputBindings()));
                }

                // The run owns one approval latch, so approval checkpoints remain deterministic.
                // Layered pipeline per step: guard (hooks + rules) first — a deny fails the
                // step with its reason (visible + replan-able), an allow skips the gate —
                // then the legacy per-step/mode approval.
                for (AgentStep step : effectiveSteps.values()) {
                    if (guard != null) {
                        ToolCallback stepTool = findTool(step.toolName(), tools);
                        ToolGuardService.GuardDecision guarded =
                                guard.decide(step.toolName(), stepTool, toJsonArgs(step.args()),
                                        cfg.effectivePermissionMode(), run.getRunId());
                        if (guarded.verdict() == ToolGuardService.Verdict.DENY) {
                            // Record the denial like any other failed step so history/UI
                            // show it — the model sees the reason and can replan around it.
                            run.addExecution(new StepExecution(step.index(),
                                    StepStatus.FAILED, guarded.reason()));
                            return new StepFailure(step.index(), guarded.reason());
                        }
                        // A step flagged requiresApproval always pauses — an allow rule or a
                        // full-access mode default must not silently skip an explicit
                        // per-step approval flag. ASK from a rule or hook forces the gate the
                        // same way (an explicit ask outranks the mode default).
                        if (step.requiresApproval() || guarded.verdict() == ToolGuardService.Verdict.ASK) {
                            run.requestApproval(AgentRunStatus.AWAITING_STEP_APPROVAL);
                            safe(sink, s -> s.onStepApprovalRequested(step.index()));
                            if (!awaitApprovalOrCancel(run)) {
                                return new StepFailure(step.index(), "cancelled awaiting step approval");
                            }
                        }
                        continue;
                    }
                    if ((cfg.requireStepApproval() && step.requiresApproval())
                            || toolRequiresApproval(step, tools, cfg.effectivePermissionMode())) {
                        run.requestApproval(AgentRunStatus.AWAITING_STEP_APPROVAL);
                        safe(sink, s -> s.onStepApprovalRequested(step.index()));
                        if (!awaitApprovalOrCancel(run)) {
                            return new StepFailure(step.index(), "cancelled awaiting step approval");
                        }
                    }
                }

                List<Callable<StepOutcome>> tasks = effectiveSteps.values().stream()
                        .<Callable<StepOutcome>>map(step ->
                                () -> executeStep(run, sink, step, results, tools))
                        .toList();
                List<Future<StepOutcome>> futures = executor.invokeAll(tasks);
                List<StepFailure> failures = new ArrayList<>();
                List<AgentStep> orderedSteps = List.copyOf(effectiveSteps.values());
                for (int i = 0; i < futures.size(); i++) {
                    AgentStep step = orderedSteps.get(i);
                    try {
                        StepOutcome outcome = futures.get(i).get();
                        if (outcome.failure() == null) {
                            completed.add(step.index());
                            pending.remove(step.index());
                        } else {
                            failures.add(outcome.failure());
                        }
                    } catch (Exception e) {
                        Throwable cause = e.getCause() == null ? e : e.getCause();
                        failures.add(new StepFailure(step.index(),
                                cause.getMessage() == null
                                        ? cause.getClass().getSimpleName()
                                        : cause.getMessage()));
                    }
                }
                if (!failures.isEmpty()) {
                    return failures.stream()
                            .min(Comparator.comparingInt(StepFailure::stepIndex))
                            .orElseThrow();
                }
            }
        }
        return null;
    }

    private StepOutcome executeStep(AgentRun run, AgentEventSink sink, AgentStep step,
                                    Map<Integer, String> results, List<ToolCallback> tools)
            throws InterruptedException {
        if (run.isCancelled()) {
            return new StepOutcome(new StepFailure(step.index(), "cancelled before step"));
        }
        run.addExecution(new StepExecution(step.index(), StepStatus.RUNNING, null));
        safe(sink, s -> s.onStepStart(step.index()));

        try {
            // A pinned step serves its canvas-authored result verbatim — the tool is never
            // invoked, but the value joins the shared results map like any other output so
            // downstream references resolve normally.
            String rawResult;
            if (step.pinnedResult() != null) {
                rawResult = step.pinnedResult();
            } else {
                rawResult = executeWithRetry(run, sink, step, tools);
            }
            // Derived outputs (flow input passthrough / result projection) materialize into a
            // COPY of the worker result — the same function for pinned, retried, and real
            // executions, so single-step debug and a full run agree byte-for-byte. The tool's
            // own inputSchema drives the sensitive-screening rule (CLI parity).
            ToolCallback stepTool = findTool(step.toolName(), tools);
            final String result = materializeOutputs(step, rawResult,
                    stepTool == null ? null : stepTool.getToolDefinition().inputSchema());
            results.put(step.index(), result);
            run.addExecution(new StepExecution(step.index(), StepStatus.COMPLETED, result));
            if (metrics != null) metrics.stepFinished(step.toolName(), "completed");
            safe(sink, s -> s.onStepComplete(step.index(), result));
            fireGuard(() -> guard.observeToolResult(step.toolName(),
                    toJsonArgs(step.args()), result, false, run.getRunId()));
            return new StepOutcome(null);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            run.addExecution(new StepExecution(step.index(), StepStatus.FAILED, msg));
            if (metrics != null) metrics.stepFinished(step.toolName(), "failed");
            fireGuard(() -> guard.observeToolResult(step.toolName(),
                    toJsonArgs(step.args()), msg, true, run.getRunId()));
            return new StepOutcome(new StepFailure(step.index(), msg));
        }
    }

    private String executeWithRetry(AgentRun run, AgentEventSink sink, AgentStep step,
                                    List<ToolCallback> tools)
            throws Exception {
        int maxAttempts = step.retryPolicy().maxAttempts();
        for (int attempt = 1; ; attempt++) {
            if (run.isCancelled()) throw new InterruptedException("cancelled before tool attempt");
            try {
                // The step arrives pre-resolved (executeSteps resolved every ready step before
                // the guard/approval pass), so guard decisions and execution agree exactly.
                AiPermissionContext.set(run.getConfig().effectivePermissionMode());
                AiRunContext.set(run.getRunId());
                fan.summer.fengyu.ai.tools.RunFileContext.set(run.getFileRefs().isEmpty()
                        ? null : run.getFileRefs());
                try {
                    return stepExecutor.execute(step, tools);
                } finally {
                    AiPermissionContext.clear();
                    AiRunContext.clear();
                    fan.summer.fengyu.ai.tools.RunFileContext.clear();
                }
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception failure) {
                if (attempt >= maxAttempts) throw failure;
                long delay = retryDelay(step.retryPolicy().backoffMs(), attempt);
                String message = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                int nextAttempt = attempt + 1;
                log.warn("agent {}: retry-safe step {} ({}) attempt {}/{} failed; retrying in {} ms",
                        run.getRunId(), step.index(), step.toolName(), attempt, maxAttempts, delay);
                safe(sink, s -> s.onStepRetry(step.index(), nextAttempt, maxAttempts,
                        delay, message));
                awaitRetry(run, delay);
            }
        }
    }

    private static long retryDelay(long initialBackoffMs, int failedAttempt) {
        if (initialBackoffMs == 0) return 0;
        long multiplier = 1L << Math.min(failedAttempt - 1, 10);
        return Math.min(30_000L, initialBackoffMs * multiplier);
    }

    // ── Derived outputs (flow input passthrough / result projection) ────

    /** Name lint shared by the schema-aware rule and the no-schema floor. */
    private static final Pattern SENSITIVE_BINDING_LINT =
            Pattern.compile("(?:password|passwd|secret|token|credential)", Pattern.CASE_INSENSITIVE);

    /** No-schema overload (unit tests, or a step whose tool cannot be resolved). */
    static String materializeOutputs(AgentStep step, String rawResult) {
        return materializeOutputs(step, rawResult, null);
    }

    /**
     * Materializes a step's {@link AgentStep#outputBindings()} into a copy of the raw result.
     * Rules (implementation plan §7.3): the worker result must be a JSON object; the original
     * object is never mutated; a binding that collides with a real worker field fails instead
     * of overwriting; input bindings read the step's EFFECTIVE (template-resolved) arguments.
     *
     * <p>When the invoking tool's {@code inputSchema} is supplied (the production executor
     * resolves the ToolCallback first), input bindings are screened with EXACTLY the
     * CLI/build rule — marked fields block, the name lint blocks unless the property
     * explicitly sets {@code x-fengyu-sensitive: false}, an unresolvable path fails — so a
     * manifest that passed {@code fengyu build} can never diverge at run time. Without a
     * schema the strict per-segment name lint remains the floor.
     */
    static String materializeOutputs(AgentStep step, String rawResult, String toolInputSchemaJson) {
        if (step.outputBindings() == null || step.outputBindings().isEmpty()) return rawResult;
        Object parsed;
        try {
            parsed = rawResult == null ? null : JsonHelper.parse(rawResult);
        } catch (Exception e) {
            throw new IllegalStateException("step " + step.index()
                    + " cannot materialize output bindings: tool result is not a JSON object", e);
        }
        if (!(parsed instanceof Map<?, ?> resultMap)) {
            throw new IllegalStateException("step " + step.index()
                    + " cannot materialize output bindings: tool result is not a JSON object");
        }
        Map<String, Object> effective = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : resultMap.entrySet()) {
            effective.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        for (AgentStep.OutputBinding binding : step.outputBindings()) {
            if (effective.containsKey(binding.name())) {
                throw new IllegalStateException("step " + step.index() + " output binding '"
                        + binding.name() + "' collides with a real result field; refusing to overwrite");
            }
            if ("input".equals(binding.source())
                    && !inputBindingAllowed(step, binding, toolInputSchemaJson)) {
                throw new IllegalStateException("step " + step.index() + " output binding '"
                        + binding.name() + "' passes through a sensitive input (" + binding.path()
                        + "); this binding must be removed");
            }
            if ("result".equals(binding.source()) && lintsSensitive(binding.path())) {
                // The runtime has no output schema to screen against; the strict name
                // lint is the floor (build/install screen result paths against the
                // tool's full outputSchema, marked fields included).
                throw new IllegalStateException("step " + step.index() + " output binding '"
                        + binding.name() + "' projects a sensitive-named result field ("
                        + binding.path() + "); this binding must be removed");
            }
            Object source = switch (binding.source()) {
                case "input" -> step.args();
                case "result" -> resultMap;
                default -> throw new IllegalStateException("unknown binding source: " + binding.source());
            };
            Object value = navigateSource(source, binding.path());
            effective.put(binding.name(), value);
        }
        return JsonHelper.toJson(effective);
    }

    /**
     * Runtime mirror of the CLI/build sensitivity rule, applied along EVERY named segment
     * of the binding path (a nested {@code smtp.password} must not slip through because the
     * root is innocuous). Schema in hand: marked blocks; lint blocks unless explicitly
     * exempted with {@code x-fengyu-sensitive: false}; an unresolvable path is a contract
     * error (the build rejects those, so only a hand-crafted plan can hit it). No schema:
     * the strict lint applies to every segment.
     */
    private static boolean inputBindingAllowed(AgentStep step, AgentStep.OutputBinding binding,
                                               String toolInputSchemaJson) {
        if (toolInputSchemaJson == null) return !lintsSensitive(binding.path());
        Object schema;
        try {
            schema = JsonHelper.parse(toolInputSchemaJson);
        } catch (Exception e) {
            return !lintsSensitive(binding.path());
        }
        if (!(schema instanceof Map<?, ?> schemaMap)) return !lintsSensitive(binding.path());
        Object node = schemaMap;
        for (String rawSegment : binding.path().split("\\.")) {
            for (String token : rawSegment.split("(?=\\[)")) {
                if (token.startsWith("[")) {
                    if (!(node instanceof Map<?, ?> map) || !(map.get("items") instanceof Map<?, ?> items)) {
                        throw unresolvableBinding(step, binding);
                    }
                    node = items;
                    continue;
                }
                String name = token.replace("]", "");
                Object props = node instanceof Map<?, ?> map ? map.get("properties") : null;
                Object next = props instanceof Map<?, ?> properties ? properties.get(name) : null;
                if (!(next instanceof Map<?, ?> prop)) throw unresolvableBinding(step, binding);
                boolean marked = Boolean.TRUE.equals(prop.get("x-fengyu-sensitive"));
                boolean explicitFalse = prop.containsKey("x-fengyu-sensitive") && !marked;
                if (marked || (!explicitFalse && SENSITIVE_BINDING_LINT.matcher(name).find())) {
                    return false;
                }
                node = prop;
            }
        }
        return true;
    }

    private static IllegalStateException unresolvableBinding(AgentStep step, AgentStep.OutputBinding binding) {
        return new IllegalStateException("step " + step.index() + " output binding '"
                + binding.name() + "' path does not resolve in the tool input schema: " + binding.path());
    }

    private static boolean lintsSensitive(String path) {
        for (String segment : path.split("[.\\[]")) {
            String name = segment.replace("]", "");
            if (!name.isEmpty() && SENSITIVE_BINDING_LINT.matcher(name).find()) return true;
        }
        return false;
    }

    /** Resolves a dotted/[N] binding path against a map/list source; missing paths fail loudly. */
    private static Object navigateSource(Object source, String dotted) {
        Object current = source;
        for (String segment : normalizePath("." + dotted).split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
            } else if (current instanceof List<?> list && segment.matches("\\d+")) {
                int index = Integer.parseInt(segment);
                current = index < list.size() ? list.get(index) : null;
            } else {
                current = null;
            }
            if (current == null) {
                throw new IllegalStateException("output binding path has no value: " + dotted);
            }
        }
        return current;
    }

    private static void awaitRetry(AgentRun run, long delayMs) throws InterruptedException {
        long remaining = delayMs;
        while (remaining > 0) {
            if (run.isCancelled()) throw new InterruptedException("cancelled during retry backoff");
            long slice = Math.min(remaining, 100L);
            Thread.sleep(slice);
            remaining -= slice;
        }
        if (run.isCancelled()) throw new InterruptedException("cancelled before retry");
    }

    /** Hook observation must never break a run — the guard itself also fails open. */
    private void fireGuard(Runnable observation) {
        if (guard == null) return;
        try {
            observation.run();
        } catch (Exception e) {
            log.warn("guard observation failed", e);
        }
    }

    private static ToolCallback findTool(String name, List<ToolCallback> tools) {
        for (ToolCallback tool : tools) {
            if (tool.getToolDefinition().name().equals(name)) return tool;
        }
        return null;
    }

    private static Set<Integer> dependencies(AgentStep step) {
        Set<Integer> dependencies = new HashSet<>(step.dependsOn());
        collectReferences(step.args(), dependencies);
        // A branch condition implies a data dependency: the condition cannot be
        // evaluated before its referenced step (typically flow_if) has produced a result.
        for (AgentStep.RunCondition condition : step.runWhen()) {
            dependencies.add(condition.step());
        }
        if (containsLastResult(step.args()) && step.index() > 0) {
            dependencies.add(step.index() - 1);
        }
        return dependencies;
    }

    // ── Control flow (runWhen branch evaluation) ──────────────────────

    /**
     * Whether a ready step is omitted by control flow: any unsatisfied branch
     * condition, a condition referencing a skipped step, or every dependency
     * having been skipped (the cascade that propagates a dead branch).
     */
    private static boolean shouldSkip(AgentStep step, Map<Integer, String> results,
                                      Set<Integer> skipped) {
        for (AgentStep.RunCondition condition : step.runWhen()) {
            if (skipped.contains(condition.step())) return true;
            if (!branchEquals(results.get(condition.step()), condition.equals())) return true;
        }
        return !step.dependsOn().isEmpty()
                && step.dependsOn().stream().allMatch(skipped::contains);
    }

    /**
     * True when a step's result object carries {@code branch == expected} — the shape
     * the built-in flow_if tool produces. Missing results, non-JSON bodies, and
     * branchless objects never satisfy a condition.
     */
    private static boolean branchEquals(String result, String expected) {
        if (result == null) return false;
        Object parsed = parsedResult(result);
        if (!(parsed instanceof Map<?, ?> map)) return false;
        Object branch = map.get("branch");
        return branch != null && String.valueOf(branch).equals(expected);
    }

    private static void collectReferences(Object value, Set<Integer> references) {
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(child -> collectReferences(child, references));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> collectReferences(child, references));
        } else if (value instanceof String text) {
            Matcher matcher = STEP_RESULT.matcher(text);
            while (matcher.find()) references.add(Integer.parseInt(matcher.group(1)));
        }
    }

    private static boolean containsLastResult(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(AgentRunner::containsLastResult);
        }
        if (value instanceof List<?> list) {
            return list.stream().anyMatch(AgentRunner::containsLastResult);
        }
        return value instanceof String text && text.contains(LAST_RESULT);
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

    private void recordRunMetrics(String runId, String status) {
        if (metrics == null) return;
        try {
            metrics.runFinished(runId, status);
        } catch (Exception ignored) {
            // Metrics must never influence a run.
        }
    }

    /** Records the terminal metric exactly once per run (P2-5). */
    private void closeRunMetrics(java.util.concurrent.atomic.AtomicBoolean metricsClosed,
                                 String runId, String status) {
        if (metricsClosed.compareAndSet(false, true)) {
            recordRunMetrics(runId, status);
        }
    }

    // ── Misc helpers ───────────────────────────────────────────────────

    /** A recorded step failure (index + message) for the replan decision. */
    private record StepFailure(int stepIndex, String message) {}
    private record StepOutcome(StepFailure failure) {}

    private static String replanGoal(String originalGoal, StepFailure failure,
                                     List<StepExecution> executions) {
        StringBuilder context = new StringBuilder(originalGoal == null ? "" : originalGoal);
        context.append("\n\nREPLAN_CONTEXT:\n")
                .append("- The previous plan failed at step ")
                .append(failure.stepIndex)
                .append(": ")
                .append(failure.message)
                .append('\n');
        List<StepExecution> completed = executions.stream()
                .filter(execution -> execution.status() == StepStatus.COMPLETED)
                .toList();
        if (!completed.isEmpty()) {
            context.append("- Completed step results that may be reused:\n");
            for (StepExecution execution : completed) {
                context.append("  - step ")
                        .append(execution.index())
                        .append(": ")
                        .append(execution.result())
                        .append('\n');
            }
        }
        context.append("- Produce a revised plan that avoids or corrects this failure.");
        return context.toString();
    }

    private static boolean toolRequiresApproval(AgentStep step, List<ToolCallback> tools,
                                                AiPermissionMode mode) {
        for (ToolCallback tool : tools) {
            if (!tool.getToolDefinition().name().equals(step.toolName())) continue;
            return ToolApprovalPolicy.requiresApproval(tool, mode, toJsonArgs(step.args()));
        }
        return false;
    }

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

    /** Validates workflows from both the model and the HTTP API before any tool is called. */
    static void validatePlan(AgentPlan plan, List<ToolCallback> tools) {
        validatePlan(plan, tools, false);
    }

    /**
     * Full validation; a read-only run additionally rejects every step whose tool is not a
     * known {@code read}-effect tool — the declared "research/review only" capability, so
     * planning/review sub-tasks can never mutate anything even with full permissions granted.
     */
    static void validatePlan(AgentPlan plan, List<ToolCallback> tools, boolean readOnly) {
        if (plan == null) throw new IllegalArgumentException("workflow is required");
        if (plan.steps() == null) throw new IllegalArgumentException("workflow steps are required");

        Set<String> available = new HashSet<>();
        if (tools != null) {
            for (ToolCallback tool : tools) available.add(tool.getToolDefinition().name());
        }
        for (int i = 0; i < plan.steps().size(); i++) {
            AgentStep step = plan.steps().get(i);
            if (step == null) throw new IllegalArgumentException("step " + i + " is null");
            if (step.index() != i) {
                throw new IllegalArgumentException("step indexes must be contiguous from 0");
            }
            if (step.toolName() == null || !available.contains(step.toolName())) {
                throw new IllegalArgumentException(
                        "step " + i + " references unavailable tool '" + step.toolName() + "'");
            }
            if (readOnly && !toolIsReadEffect(step.toolName(), tools)) {
                throw new IllegalArgumentException("step " + i + " uses non-read tool '"
                        + step.toolName() + "'; this run is read-only (research/review)");
            }
            AgentStep.RetryPolicy retry = step.retryPolicy();
            if (retry.maxAttempts() < 1 || retry.maxAttempts() > 5) {
                throw new IllegalArgumentException("step " + i
                        + " maxAttempts must be between 1 and 5");
            }
            if (retry.backoffMs() < 0 || retry.backoffMs() > 30_000) {
                throw new IllegalArgumentException("step " + i
                        + " backoffMs must be between 0 and 30000");
            }
            if (retry.maxAttempts() > 1 && !toolIsRetrySafe(step.toolName(), tools)) {
                throw new IllegalArgumentException("step " + i + " requests retries for tool '"
                        + step.toolName() + "', but that tool is not retry-safe");
            }
            for (Integer dependency : step.dependsOn()) {
                if (dependency == null || dependency < 0 || dependency >= i) {
                    throw new IllegalArgumentException(
                            "step " + i + " has invalid dependency " + dependency);
                }
            }
            for (AgentStep.RunCondition condition : step.runWhen()) {
                if (condition == null || condition.step() < 0 || condition.step() >= i
                        || condition.equals() == null || condition.equals().isBlank()) {
                    throw new IllegalArgumentException("step " + i
                            + " has invalid runWhen condition " + condition);
                }
            }
            validateReferences(step.args(), i);
        }
    }

    private static boolean toolIsReadEffect(String toolName, List<ToolCallback> tools) {
        for (ToolCallback tool : tools) {
            if (!tool.getToolDefinition().name().equals(toolName)) continue;
            return tool instanceof fan.summer.fengyu.ai.tools.AuditedToolCallback audited
                    && audited.effect() == ToolEffect.READ;
        }
        return false;
    }

    private static boolean toolIsRetrySafe(String toolName, List<ToolCallback> tools) {
        for (ToolCallback tool : tools) {
            if (!tool.getToolDefinition().name().equals(toolName)) continue;
            return tool instanceof fan.summer.fengyu.ai.tools.AuditedToolCallback audited
                    && audited.retrySafe();
        }
        return false;
    }

    private static void validateReferences(Object value, int currentIndex) {
        if (value instanceof Map<?, ?> map) {
            for (Object child : map.values()) validateReferences(child, currentIndex);
        } else if (value instanceof List<?> list) {
            for (Object child : list) validateReferences(child, currentIndex);
        } else if (value instanceof String text) {
            Matcher matcher = STEP_RESULT.matcher(text);
            while (matcher.find()) {
                int referenced = Integer.parseInt(matcher.group(1));
                if (referenced >= currentIndex) {
                    throw new IllegalArgumentException(
                            "step " + currentIndex + " references non-previous step " + referenced);
                }
            }
            if (text.contains(LAST_RESULT) && currentIndex == 0) {
                throw new IllegalArgumentException("step 0 cannot reference last.result");
            }
        }
    }

    private static Map<String, Object> resolveArgs(Map<String, Object> args,
                                                   Map<Integer, String> results,
                                                   String lastResult) {
        if (args == null || args.isEmpty()) return Map.of();
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            resolved.put(entry.getKey(), resolveValue(entry.getValue(), results, lastResult));
        }
        return resolved;
    }

    private static Object resolveValue(Object value, Map<Integer, String> results, String lastResult) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                resolved.put(String.valueOf(entry.getKey()),
                        resolveValue(entry.getValue(), results, lastResult));
            }
            return resolved;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(v -> resolveValue(v, results, lastResult)).toList();
        }
        if (!(value instanceof String text)) return value;

        if (LAST_RESULT.equals(text)) return parsedResult(lastResult);
        Matcher exact = STEP_RESULT.matcher(text);
        if (exact.matches()) {
            return referencedResult(requiredResult(results, Integer.parseInt(exact.group(1))), exact.group(2));
        }

        String replaced = text.replace(LAST_RESULT, lastResult == null ? "" : lastResult);
        Matcher matcher = STEP_RESULT.matcher(replaced);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String result = requiredResult(results, Integer.parseInt(matcher.group(1)));
            Object referenced = referencedResult(result, matcher.group(2));
            String rendered = referenced instanceof String string ? string : JsonHelper.toJson(referenced);
            matcher.appendReplacement(output, Matcher.quoteReplacement(rendered));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String requiredResult(Map<Integer, String> results, int index) {
        if (!results.containsKey(index)) {
            throw new IllegalArgumentException("No result is available for step " + index);
        }
        return results.get(index);
    }

    private static Object parsedResult(String result) {
        if (result == null) return null;
        try {
            Object parsed = JsonHelper.parse(result);
            return parsed == null ? result : parsed;
        } catch (Exception ignored) {
            return result;
        }
    }

    private static Object referencedResult(String result, String dottedPath) {
        Object parsed = parsedResult(result);
        if (dottedPath == null || dottedPath.isEmpty()) return parsed;
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Tool result is not an object; cannot read " + dottedPath);
        }
        String path = normalizePath(dottedPath);
        @SuppressWarnings("unchecked")
        Object value = JsonHelper.navigate((Map<String, Object>) map, path);
        if (value == null) {
            throw new IllegalArgumentException("Tool result has no output field " + path);
        }
        return value;
    }

    /**
     * Converts reference path segments into JsonHelper.navigate's vocabulary: array
     * indexes become numeric dotted segments ({@code .files[2].name} → {@code files.2.name}),
     * which navigate resolves against both map keys and list positions.
     */
    static String normalizePath(String dottedPath) {
        return dottedPath.substring(1).replace("[", ".").replace("]", "");
    }
}
