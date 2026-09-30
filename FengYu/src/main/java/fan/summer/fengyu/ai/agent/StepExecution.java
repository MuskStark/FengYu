package fan.summer.fengyu.ai.agent;

/**
 * The outcome of executing (or attempting to execute) a single {@link AgentStep}.
 *
 * <p>The AgentRunner (Task 15) appends a {@code StepExecution} to {@link AgentRun#getExecutions()}
 * as each step progresses; the record is immutable so historical entries are never mutated in
 * place — state transitions are recorded by appending a fresh entry.
 *
 * @param index  the index of the {@link AgentStep} this execution refers to
 * @param status the {@link StepStatus} reached for this step
 * @param result the tool's result text on success, an error message on failure, or {@code null}
 *               when the step has not yet produced output (e.g. PENDING / SKIPPED)
 */
public record StepExecution(int index,
                            StepStatus status,
                            String result) {
}
