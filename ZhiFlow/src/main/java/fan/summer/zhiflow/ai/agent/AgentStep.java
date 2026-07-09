package fan.summer.zhiflow.ai.agent;

import java.util.Map;

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
 * @param description       human-readable explanation of what the step does and why
 * @param requiresApproval  whether this step must pause for human approval before executing
 */
public record AgentStep(int index,
                        String toolName,
                        Map<String, Object> args,
                        String description,
                        boolean requiresApproval) {
}
