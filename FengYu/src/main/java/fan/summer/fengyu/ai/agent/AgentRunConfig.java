package fan.summer.fengyu.ai.agent;

import fan.summer.fengyu.ai.tools.AiPermissionMode;

/**
 * Configuration controlling the approval and recovery behavior of a Plan-and-Execute agent run.
 *
 * <p>This is supplied when constructing an {@link AgentRun} and is consulted by the AgentRunner
 * (Task 15) to decide which synchronization points to honor and how to react to step failures.
 *
 * @param requirePlanApproval if {@code true}, the run pauses on
 *                            {@link AgentRunStatus#AWAITING_PLAN_APPROVAL} after planning until
 *                            a human approves (and optionally edits) the plan
 * @param requireStepApproval if {@code true}, steps flagged
 *                            {@link AgentStep#requiresApproval()} pause on
 *                            {@link StepStatus#AWAITING_APPROVAL} before their result is accepted
 * @param replanOnFailure     if {@code true}, a failed step triggers a replanning round
 *                            (up to {@link #maxReplans()}) before the run is marked
 *                            {@link AgentRunStatus#FAILED}
 * @param maxReplans          the maximum number of replanning rounds permitted before giving up;
 *                            ignored when {@code replanOnFailure} is {@code false}
 */
public record AgentRunConfig(boolean requirePlanApproval,
                             boolean requireStepApproval,
                             boolean replanOnFailure,
                             int maxReplans,
                             AiPermissionMode permissionMode) {
    public AgentRunConfig(boolean requirePlanApproval, boolean requireStepApproval,
                          boolean replanOnFailure, int maxReplans) {
        this(requirePlanApproval, requireStepApproval, replanOnFailure, maxReplans,
                AiPermissionMode.ASK_FOR_APPROVAL);
    }

    public AiPermissionMode effectivePermissionMode() {
        return permissionMode == null ? AiPermissionMode.ASK_FOR_APPROVAL : permissionMode;
    }
}
