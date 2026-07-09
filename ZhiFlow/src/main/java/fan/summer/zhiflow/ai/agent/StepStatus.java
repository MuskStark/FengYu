package fan.summer.zhiflow.ai.agent;

/**
 * Lifecycle state of a single {@link AgentStep} within an agent run.
 *
 * <p>The state machine transitions enforced by the AgentRunner (Task 15) are:
 * <pre>
 *   PENDING → RUNNING → COMPLETED          (success)
 *           ↘           → FAILED            (tool error)
 *           ↘ AWAITING_APPROVAL → COMPLETED (after approve) / FAILED (after reject)
 *   PENDING → SKIPPED                       (plan omitted or rejected it)
 * </pre>
 */
public enum StepStatus {
    /** The step has been planned but not yet started. */
    PENDING,
    /** The step's tool is currently executing. */
    RUNNING,
    /** The step requires human approval before its result is accepted; execution is paused. */
    AWAITING_APPROVAL,
    /** The step finished successfully. */
    COMPLETED,
    /** The step's tool threw an error or was rejected on approval. */
    FAILED,
    /** The step was intentionally not executed (e.g. dropped during replanning). */
    SKIPPED
}
