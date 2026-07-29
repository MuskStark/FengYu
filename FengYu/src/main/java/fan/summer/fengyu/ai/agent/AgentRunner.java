package fan.summer.fengyu.ai.agent;

import fan.summer.fengyu.ai.util.JsonHelper;
import fan.summer.fengyu.ai.tools.ApprovalRequiredToolCallback;
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
    private static final Pattern STEP_RESULT =
            Pattern.compile("\\{\\{steps\\.(\\d+)\\.result}}");
    private static final String LAST_RESULT = "{{last.result}}";

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
                    validatePlan(plan, tools);
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
                    validatePlan(plan, tools);
                } catch (Exception e) {
                    run.setStatus(AgentRunStatus.FAILED);
                    safe(sink, s -> s.onError("Invalid workflow: " + e.getMessage()));
                    return;
                }
                run.setStatus(AgentRunStatus.EXECUTING);
                StepFailure failure = executeSteps(run, sink, cfg, plan);

                if (failure == null) {
                    // All steps completed → terminal success.
                    String summary = "Completed " + plan.steps().size() + " step(s) for goal: " + plan.goal();
                    run.setStatus(AgentRunStatus.COMPLETED);
                    safe(sink, s -> s.onComplete(summary));
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

    /**
     * Executes dependency-ready DAG levels concurrently on virtual threads. Approval gates are
     * resolved before a level starts, so no worker can leave a sibling blocked behind a shared
     * run-level approval latch.
     */
    private StepFailure executeSteps(AgentRun run, AgentEventSink sink, AgentRunConfig cfg, AgentPlan plan)
            throws InterruptedException {
        Map<Integer, String> results = Collections.synchronizedMap(new HashMap<>());
        Set<Integer> completed = new HashSet<>();
        for (StepExecution execution : run.getRestoredExecutions()) {
            completed.add(execution.index());
            results.put(execution.index(), execution.result());
        }

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

                // The run owns one approval latch, so approval checkpoints remain deterministic.
                for (AgentStep step : ready) {
                    if ((cfg.requireStepApproval() && step.requiresApproval())
                            || toolRequiresApproval(step.toolName(), tools)) {
                        run.requestApproval(AgentRunStatus.AWAITING_STEP_APPROVAL);
                        safe(sink, s -> s.onStepApprovalRequested(step.index()));
                        if (!awaitApprovalOrCancel(run)) {
                            return new StepFailure(step.index(), "cancelled awaiting step approval");
                        }
                    }
                }

                List<Callable<StepOutcome>> tasks = ready.stream()
                        .<Callable<StepOutcome>>map(step ->
                                () -> executeStep(run, sink, step, results))
                        .toList();
                List<Future<StepOutcome>> futures = executor.invokeAll(tasks);
                List<StepFailure> failures = new ArrayList<>();
                for (int i = 0; i < futures.size(); i++) {
                    AgentStep step = ready.get(i);
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
                                    Map<Integer, String> results) {
        if (run.isCancelled()) {
            return new StepOutcome(new StepFailure(step.index(), "cancelled before step"));
        }
        run.addExecution(new StepExecution(step.index(), StepStatus.RUNNING, null));
        safe(sink, s -> s.onStepStart(step.index()));

        try {
            AgentStep resolved = new AgentStep(step.index(), step.toolName(),
                    resolveArgs(step.args(), results, results.get(step.index() - 1)),
                    step.description(), step.requiresApproval(), step.dependsOn());
            String result = stepExecutor.execute(resolved, tools);
            results.put(step.index(), result);
            run.addExecution(new StepExecution(step.index(), StepStatus.COMPLETED, result));
            safe(sink, s -> s.onStepComplete(step.index(), result));
            return new StepOutcome(null);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            run.addExecution(new StepExecution(step.index(), StepStatus.FAILED, msg));
            return new StepOutcome(new StepFailure(step.index(), msg));
        }
    }

    private static Set<Integer> dependencies(AgentStep step) {
        Set<Integer> dependencies = new HashSet<>(step.dependsOn());
        collectReferences(step.args(), dependencies);
        if (containsLastResult(step.args()) && step.index() > 0) {
            dependencies.add(step.index() - 1);
        }
        return dependencies;
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

    private static boolean toolRequiresApproval(String toolName, List<ToolCallback> tools) {
        for (ToolCallback tool : tools) {
            if (tool instanceof ApprovalRequiredToolCallback
                    && tool.getToolDefinition().name().equals(toolName)) {
                return true;
            }
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
            for (Integer dependency : step.dependsOn()) {
                if (dependency == null || dependency < 0 || dependency >= i) {
                    throw new IllegalArgumentException(
                            "step " + i + " has invalid dependency " + dependency);
                }
            }
            validateReferences(step.args(), i);
        }
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
            return parsedResult(requiredResult(results, Integer.parseInt(exact.group(1))));
        }

        String replaced = text.replace(LAST_RESULT, lastResult == null ? "" : lastResult);
        Matcher matcher = STEP_RESULT.matcher(replaced);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String result = requiredResult(results, Integer.parseInt(matcher.group(1)));
            matcher.appendReplacement(output, Matcher.quoteReplacement(result));
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
}
