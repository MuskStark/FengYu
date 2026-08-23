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
 * @param retryPolicy       bounded retry policy. More than one attempt is accepted only for a
 *                          tool whose callback declares the invocation retry-safe.
 * @param outputBindings    explicit derived outputs (flow input passthrough / result
 *                          projection): after the tool call (or a pinned result), the runner
 *                          materializes each binding into a COPY of the worker result so
 *                          downstream {@code {{steps.N.result.<name>}}} references resolve.
 *                          Null/empty (every stored plan before this field) keeps the raw
 *                          worker result unchanged.
 */
public record AgentStep(int index,
                        String toolName,
                        Map<String, Object> args,
                        String description,
                        boolean requiresApproval,
                        List<Integer> dependsOn,
                        String pinnedResult,
                        List<RunCondition> runWhen,
                        RetryPolicy retryPolicy,
                        List<OutputBinding> outputBindings) {

    public AgentStep {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        runWhen = runWhen == null ? List.of() : List.copyOf(runWhen);
        retryPolicy = retryPolicy == null ? RetryPolicy.NONE : retryPolicy;
        outputBindings = outputBindings == null ? List.of() : List.copyOf(outputBindings);
    }

    /**
     * One derived output: value {@code input.<path>} is read from the step's
     * post-resolution effective arguments, {@code result.<path>} from the parsed
     * worker result object; it is written to the effective result under
     * {@code name}, never overwriting a real worker field.
     */
    public record OutputBinding(String name, String source, String path) {
        public OutputBinding {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("output binding name is required");
            if (!"input".equals(source) && !"result".equals(source)) {
                throw new IllegalArgumentException("output binding source must be 'input' or 'result': " + source);
            }
            if (path == null || path.isBlank()) throw new IllegalArgumentException("output binding path is required");
        }
    }

    /**
     * One branch condition: step {@code step}'s {@code branch} output must equal
     * {@code equals} ("true"/"false" for the built-in flow_if node).
     */
    public record RunCondition(int step, String equals) {}

    /** A total-attempt count and the initial exponential-backoff delay in milliseconds. */
    public record RetryPolicy(int maxAttempts, long backoffMs) {
        public static final RetryPolicy NONE = new RetryPolicy(1, 0);
    }

    /** Backward-compatible constructor for stored plans and callers created before DAG support. */
    public AgentStep(int index, String toolName, Map<String, Object> args,
                     String description, boolean requiresApproval) {
        this(index, toolName, args, description, requiresApproval, List.of(), null, List.of(), null, null);
    }

    /** DAG constructor without a pinned result (the common compiled-workflow shape). */
    public AgentStep(int index, String toolName, Map<String, Object> args,
                     String description, boolean requiresApproval, List<Integer> dependsOn) {
        this(index, toolName, args, description, requiresApproval, dependsOn, null, List.of(), null, null);
    }

    /** DAG constructor with a pinned result but no branch conditions. */
    public AgentStep(int index, String toolName, Map<String, Object> args,
                     String description, boolean requiresApproval, List<Integer> dependsOn,
                     String pinnedResult) {
        this(index, toolName, args, description, requiresApproval, dependsOn, pinnedResult, List.of(), null, null);
    }

    /** Full pre-retry constructor retained for stored plans written before retry policies. */
    public AgentStep(int index, String toolName, Map<String, Object> args,
                     String description, boolean requiresApproval, List<Integer> dependsOn,
                     String pinnedResult, List<RunCondition> runWhen) {
        this(index, toolName, args, description, requiresApproval, dependsOn, pinnedResult,
                runWhen, null, null);
    }

    /** Full constructor retained for stored plans written before output bindings. */
    public AgentStep(int index, String toolName, Map<String, Object> args,
                     String description, boolean requiresApproval, List<Integer> dependsOn,
                     String pinnedResult, List<RunCondition> runWhen, RetryPolicy retryPolicy) {
        this(index, toolName, args, description, requiresApproval, dependsOn, pinnedResult,
                runWhen, retryPolicy, null);
    }
}
