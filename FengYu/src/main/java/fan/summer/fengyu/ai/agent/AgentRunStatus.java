package fan.summer.fengyu.ai.agent;

/**
 * Lifecycle state of a Plan-and-Execute {@link AgentRun}.
 *
 * <p>The high-level state machine driven by the AgentRunner (Task 15) is:
 * <pre>
 *   PLANNING → AWAITING_PLAN_APPROVAL → EXECUTING → AWAITING_STEP_APPROVAL → EXECUTING → ... → COMPLETED
 *          ↘                                                                ↘           → FAILED
 *          ↘                                                                              → CANCELLED
 * </pre>
 * Any state may transition to {@link #CANCELLED} when the user cancels the run.
 */
public enum AgentRunStatus {
    /** The planner is producing an {@link AgentPlan} for the run's goal. */
    PLANNING,
    /** A plan has been produced and the run is paused waiting for human approval of it. */
    AWAITING_PLAN_APPROVAL,
    /** The plan was approved (or approval was skipped) and steps are being executed. */
    EXECUTING,
    /** A step flagged {@code requiresApproval} is paused waiting for human approval. */
    AWAITING_STEP_APPROVAL,
    /** All steps completed successfully. */
    COMPLETED,
    /** A step failed and the run could not recover (e.g. replanning exhausted). */
    FAILED,
    /** The process restarted mid-run; completed steps are durable and the remainder awaits review. */
    RECOVERY_REQUIRED,
    /** The user cancelled the run. */
    CANCELLED
}
