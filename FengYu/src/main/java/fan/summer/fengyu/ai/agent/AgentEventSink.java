package fan.summer.fengyu.ai.agent;

/**
 * SSE-agnostic callback interface that receives the lifecycle events of a single
 * Plan-and-Execute {@link AgentRun} driven by {@link AgentRunner}.
 *
 * <p>This interface exists primarily to make {@link AgentRunner} testable without an
 * SSE/HTTP transport: a unit test supplies a recording implementation, drives a run with
 * scripted planning + tools, and asserts on the sequence of callbacks. In production the
 * controller (Task 16) wires an SSE-backed sink that forwards each event to the client as
 * a {@code text/event-stream} chunk.
 *
 * <p>Implementations <em>must</em> be safe to call from the runner's virtual thread; they
 * should not block (the runner emits {@link #onPlanToken(String)} token-by-token, and any
 * slow work in a sink would stall the whole orchestration).
 *
 * <p>The eight events, in their canonical happy-path order, are:
 * <ol>
 *   <li>{@link #onPlanToken} — zero or more times while the plan is being generated.</li>
 *   <li>{@link #onPlanReady} — once, when the {@link AgentPlan} is finalized.</li>
 *   <li>{@link #onPlanApprovalRequested} — only when plan approval is required.</li>
 *   <li>{@link #onStepStart} — once per executed step, before the tool runs.</li>
 *   <li>{@link #onStepApprovalRequested} — only for steps flagged
 *       {@link AgentStep#requiresApproval()} under an approval-requiring config.</li>
 *   <li>{@link #onStepComplete} — once per executed step, with the tool's result text.</li>
 *   <li>{@link #onComplete} — exactly once on success, OR</li>
 *   <li>{@link #onError} — exactly once on terminal failure.</li>
 * </ol>
 * Exactly one of {@link #onComplete} / {@link #onError} terminates the run.
 */
public interface AgentEventSink {

    /** A token of plan-generation output (e.g. streamed LLM tokens). May be called zero or more times. */
    void onPlanToken(String delta);

    /** The finalized plan has been produced (and, if approval is required, is awaiting approval). */
    void onPlanReady(AgentPlan plan);

    /** The run is paused waiting for human approval of the plan. */
    void onPlanApprovalRequested();

    /** Execution of the step at {@code index} is starting (its tool is about to run). */
    void onStepStart(int index);

    /** The step at {@code index} finished with the given result text. */
    void onStepComplete(int index, String result);

    /** The step at {@code index} is paused waiting for human approval before its result is accepted. */
    void onStepApprovalRequested(int index);

    /** The run completed successfully; {@code summary} is the final result text. */
    void onComplete(String summary);

    /** The run failed terminally; {@code message} describes why. */
    void onError(String message);
}
