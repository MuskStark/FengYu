package fan.summer.fengyu.ai.agent;

import java.util.Map;
import java.util.List;

/**
 * A single planned action within an {@link AgentPlan}.
 *
 * <p>Each step maps to one invocation of the named tool with the given arguments. The
 * {@code requiresApproval} flag is set by the planner when a step is potentially destructive
 * or otherwise needs a human in the loop; the AgentRunner (Task 15) consults it together with
 * {@link AgentRunConfig#requireStepApproval()} to decide whether to pause on
 * {@link StepStatus#AWAITING_APPROVAL} before executing.
 *
 * @param index             positional index within the plan (0-based)
 * @param toolName          the name of the tool to invoke (resolvable by the tool registry)
 * @param args              the tool arguments as a JSON-like map; never mutated by this record
 * @param description       human-readable explanation of what this step does and why
 * @param requiresApproval  whether this step must pause for human approval before executing
 * @param dependsOn         indexes that must complete before this step can start
 * @param pinnedResult      canvas-authored fixed result; when set the runner serves this value
 *                          instead of calling the tool (the flow builder's "pin output" debug
 *                          affordance). Null for normal steps.
 * @param runWhen           branch conditions (canvas control flow): the step is SKIPPED unless
 *                          every condition holds, where a condition holds when the referenced
 *                          step's result object carries {@code branch == equals} (the flow_if
 *                          tool's output). Null/empty means "always run" — the pre-control-flow
 *                          shape every stored plan still has.
 */
public record AgentStep(int index,
                        String toolName,
                        Map<String, Object> args,
                        String description,
                        boolean requiresApproval,
                        List<Integer> dependsOn,
                        String pinnedResult,
                        List<RunCondition> runWhen) {

    public AgentStep {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        runWhen = runWhen == null ? List.of() : List.copyOf(runWhen);
    }

    /**
     * One branch condition: step {@code step}'s {@code branch} output must equal
     * {@code equals} ("true"/"false" for the built-in flow_if node).
     */
    public record RunCondition(int step, String equals) {}

    /** Backward-compatible constructor for stored plans and callers created before DAG support. */
    public AgentStep(int index, String toolName, Map<String, Object> args,
                     String description, boolean requiresApproval) {
        this(index, toolName, args, description, requiresApproval, List.of(), null, List.of());
    }

    /** DAG constructor without a pinned result (the common compiled-workflow shape). */
    public AgentStep(int index, String toolName, Map<String, Object> args,
                     String description, boolean requiresApproval, List<Integer> dependsOn) {
        this(index, toolName, args, description, requiresApproval, dependsOn, null, List.of());
    }

    /** DAG constructor with a pinned result but no branch conditions. */
    public AgentStep(int index, String toolName, Map<String, Object> args,
                     String description, boolean requiresApproval, List<Integer> dependsOn,
                     String pinnedResult) {
        this(index, toolName, args, description, requiresApproval, dependsOn, pinnedResult, List.of());
    }
}
