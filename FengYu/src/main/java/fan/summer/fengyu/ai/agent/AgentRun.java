package fan.summer.fengyu.ai.agent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import fan.summer.fengyu.ai.ChatFileContext;

/**
 * The stateful runtime container for a single Plan-and-Execute agent run.
 *
 * <p>An {@code AgentRun} holds the immutable goal and config supplied at construction plus the
 * mutable run state: the current {@link AgentRunStatus}, the approved {@link AgentPlan}, the
 * accumulated {@link StepExecution}s, and a cancellation flag. It also owns the
 * <em>approval gate</em> — a {@link CountDownLatch}-based synchronization primitive that lets the
 * AgentRunner (Task 15) block on {@link #awaitApproval()} until an external caller (the
 * controller / user) releases it via {@link #approve(AgentPlan)}.
 *
 * <p>All mutable state is {@code volatile} or backed by a thread-safe collection so that the run
 * can be driven by an executor thread while a UI/controller thread reads state and posts
 * approvals concurrently.
 */
public class AgentRun {

    private final String runId;
    /** The user goal — read by the AgentRunner during the planning phase. */
    private final String goal;
    private final AgentRunConfig config;
    private final long userId;

    private volatile AgentRunStatus status = AgentRunStatus.PLANNING;
    private volatile boolean cancelled = false;
    private volatile AgentPlan plan;
    private volatile Thread runnerThread;
    /** Stable root id used to derive per-step invocation ids across an interrupted resume. */
    private volatile String invocationScope;
    /**
     * True for runs no interactive client is expected to follow (schedules, webhook
     * deliveries, AI background tasks). The runner uses it to fail approval gates on a
     * short timeout instead of blocking forever — nobody is watching the stream.
     */
    private volatile boolean unattended = false;

    private final List<StepExecution> executions = new CopyOnWriteArrayList<>();
    private final List<StepExecution> restoredExecutions = new CopyOnWriteArrayList<>();

    /**
     * Run-scoped file grants keyed by workflow input name (see {@code RunFileContext}). Volatile
     * and never persisted: a resumed run whose steps still carry {@code @file:<name>} placeholders
     * fails those steps with the injector's explicit "no granted file" error instead of a silent
     * wrong-path read.
     */
    private volatile Map<String, List<ChatFileContext.ActiveFileRef>> fileRefs = Map.of();

    /**
     * The approval gate. A fresh count-1 latch is created by {@link #requestApproval(AgentRunStatus)};
     * {@link #approve(AgentPlan)} counts it down, releasing any thread blocked in
     * {@link #awaitApproval()}. Marked {@code volatile} so a latch installed by the executor thread
     * is reliably observed by the thread that will await it.
     */
    private volatile CountDownLatch approvalGate = new CountDownLatch(0);

    /**
     * Credential of the currently armed gate: a fresh UUID per {@link #requestApproval(AgentRunStatus)},
     * exposed to the client on the approval-request event and required (when supplied) by
     * {@link #approve(AgentPlan, String)}. Without it, a repeated or late approve could release
     * the NEXT gate a run armed after the first one was approved. {@code null} while no gate
     * is armed.
     */
    private volatile String approvalGateId;

    /**
     * True while the currently armed gate has already been released. A duplicate/late
     * {@link #approve(AgentPlan, String)} — including the legacy credential-less shape the
     * current frontend sends — conflicts instead of releasing whatever gate the run armed
     * next: without this flag a double-clicked approve would pass the awaiting-state check
     * against the NEXT gate's {@code AWAITING_*} status.
     */
    private volatile boolean approvalGateResolved = true;

    /**
     * @param runId  unique identifier for this run
     * @param goal   the user goal the run will plan and execute against
     * @param config the approval/recovery configuration; must not be {@code null}
     */
    public AgentRun(String runId, String goal, AgentRunConfig config) {
        this(runId, goal, config, fan.summer.fengyu.database.SecurityConstants.LOCAL_VIRTUAL_USER_ID);
    }

    public AgentRun(String runId, String goal, AgentRunConfig config, long userId) {
        this.runId = runId;
        this.goal = goal;
        this.config = config;
        this.userId = userId;
        this.invocationScope = runId;
    }

    /** @return the unique identifier for this run. */
    public String getRunId() {
        return runId;
    }

    /** @return the user goal this run is working towards. */
    public String getGoal() {
        return goal;
    }

    /** @return the run's approval/recovery configuration. */
    public AgentRunConfig getConfig() {
        return config;
    }

    public long getUserId() { return userId; }

    public void setInvocationScope(String invocationScope) {
        if (invocationScope != null && !invocationScope.isBlank()) this.invocationScope = invocationScope;
    }

    public String invocationId(int stepIndex) {
        return invocationScope + ":step:" + stepIndex;
    }

    /** Attaches run-scoped file grants (workflow file inputs resolved before the run started). */
    public void attachFileRefs(Map<String, List<ChatFileContext.ActiveFileRef>> fileRefs) {
        this.fileRefs = fileRefs == null ? Map.of() : Map.copyOf(fileRefs);
    }

    public Map<String, List<ChatFileContext.ActiveFileRef>> getFileRefs() {
        return fileRefs;
    }

    /** @return the current {@link AgentRunStatus}. */
    public AgentRunStatus getStatus() {
        return status;
    }

    /** Sets the current run status (called by the AgentRunner as it advances the state machine). */
    public void setStatus(AgentRunStatus status) {
        this.status = status;
    }

    /** @return the approved plan, or {@code null} if planning has not yet completed. */
    public AgentPlan getPlan() {
        return plan;
    }

    /** Sets the approved plan (called by {@link #approve(AgentPlan)} or directly by the runner). */
    public void setPlan(AgentPlan plan) {
        this.plan = plan;
    }

    /**
     * @return a thread-safe, unmodifiable view of the accumulated {@link StepExecution}s.
     *         The backing {@link CopyOnWriteArrayList} is safe to iterate concurrently with
     *         appends.
     */
    public List<StepExecution> getExecutions() {
        return List.copyOf(executions);
    }

    /** Appends a {@link StepExecution} recording the outcome of a step transition. */
    public void addExecution(StepExecution execution) {
        executions.add(execution);
    }

    /**
     * Restores completed execution state from a persisted interrupted run. Restored entries are
     * tracked separately so ordinary failure replanning never mistakes an earlier plan's step
     * index for an already-completed step in a newly generated plan.
     */
    public void restoreExecutions(List<StepExecution> restored) {
        if (restored == null) return;
        restored.stream()
                .filter(execution -> execution != null
                        && execution.status() == StepStatus.COMPLETED)
                .forEach(execution -> {
                    restoredExecutions.add(execution);
                    executions.add(execution);
                });
    }

    public List<StepExecution> getRestoredExecutions() {
        return List.copyOf(restoredExecutions);
    }

    /** Marks the run as cancelled (terminal). Idempotent. */
    public void markCancelled() {
        this.cancelled = true;
        Thread runner = runnerThread;
        if (runner != null) runner.interrupt();
    }

    void attachRunnerThread(Thread thread) { this.runnerThread = thread; }
    void detachRunnerThread(Thread thread) {
        if (this.runnerThread == thread) this.runnerThread = null;
    }

    /** @return {@code true} if the run has been cancelled via {@link #markCancelled()}. */
    public boolean isCancelled() {
        return cancelled;
    }

    // ---- Approval gate ------------------------------------------------------

    /** Marks the run as unattended (schedules, webhooks, AI background tasks). */
    public void markUnattended() {
        this.unattended = true;
    }

    /** @return {@code true} when no interactive client is expected to follow this run. */
    public boolean isUnattended() {
        return unattended;
    }

    /**
     * Arms the approval gate: installs a fresh count-1 latch (with a fresh gate credential)
     * so that the next {@link #awaitApproval()} will block, and transitions the run to the
     * given awaiting status ({@link AgentRunStatus#AWAITING_PLAN_APPROVAL} or
     * {@link AgentRunStatus#AWAITING_STEP_APPROVAL}).
     *
     * <p>Called by the AgentRunner when it reaches a synchronization point (plan produced, or a
     * step flagged {@link AgentStep#requiresApproval()}).
     *
     * @param awaitingStatus the status to record while waiting for approval
     */
    public void requestApproval(AgentRunStatus awaitingStatus) {
        synchronized (this) {
            this.approvalGate = new CountDownLatch(1);
            this.approvalGateId = UUID.randomUUID().toString();
            this.approvalGateResolved = false;
            this.status = awaitingStatus;
        }
    }

    /** @return the credential of the currently armed gate, or {@code null} when none is armed. */
    public String getApprovalGateId() {
        return approvalGateId;
    }

    /**
     * Releases the approval gate (counts the latch down to zero), waking any thread blocked in
     * {@link #awaitApproval()}. Optionally applies an edited plan when the approval was for a
     * plan (a {@code null} argument leaves the current plan unchanged, i.e. simple approval).
     *
     * <p>Unchecked variant for internal callers: the cancellation bridge (cancel releases an
     * armed gate so the runner can observe the cancellation promptly) and tests.
     *
     * @param edited the edited plan to install, or {@code null} to keep the current plan
     */
    public void approve(AgentPlan edited) {
        CountDownLatch gate;
        synchronized (this) {
            if (edited != null) {
                this.plan = edited;
            }
            this.approvalGateResolved = true;
            gate = this.approvalGate;
        }
        gate.countDown();
    }

    /**
     * Credential-checked release for the user-facing approve endpoint. Rejects with
     * {@link ApprovalConflictException} when the run is not currently awaiting an approval,
     * when the armed gate was already released (a duplicate or late approve must never
     * release a gate the run armed afterwards), or when the supplied gate credential does
     * not match the armed gate. A {@code null} gateId stays accepted for legacy clients that
     * never saw a gateId; those are still protected by the awaiting-state and
     * already-resolved checks.
     *
     * @param edited the edited plan to install, or {@code null} to keep the current plan
     * @param gateId the gate credential from the approval-request event, or {@code null}
     */
    public void approve(AgentPlan edited, String gateId) {
        synchronized (this) {
            AgentRunStatus current = this.status;
            if (current != AgentRunStatus.AWAITING_PLAN_APPROVAL
                    && current != AgentRunStatus.AWAITING_STEP_APPROVAL) {
                throw new ApprovalConflictException("Run " + runId + " is not awaiting approval ("
                        + current + "); the approval was already resolved or the run has moved on");
            }
            if (approvalGateResolved) {
                throw new ApprovalConflictException("Run " + runId
                        + " is awaiting approval but its current gate was already released; "
                        + "this duplicate or late approve was ignored");
            }
            String armed = this.approvalGateId;
            if (gateId != null && !gateId.equals(armed)) {
                throw new ApprovalConflictException("Approval gate mismatch for run " + runId
                        + ": the credential belongs to an already-released gate");
            }
        }
        approve(edited);
    }

    /**
     * Blocks the calling thread until {@link #approve(AgentPlan)} releases the gate.
     *
     * <p>If the gate was never armed (no {@link #requestApproval(AgentRunStatus)} call) the latch
     * is already at zero and this returns immediately.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public void awaitApproval() throws InterruptedException {
        approvalGate.await();
    }

    /**
     * Timed variant of {@link #awaitApproval()} used by the runner for unattended runs.
     *
     * @return {@code true} if the gate was released, {@code false} on timeout
     */
    public boolean awaitApproval(long timeout, TimeUnit unit) throws InterruptedException {
        return approvalGate.await(timeout, unit);
    }

    /** A duplicate/late/stale-credential approve attempt; the controller maps it to HTTP 409. */
    public static final class ApprovalConflictException extends IllegalStateException {
        public ApprovalConflictException(String message) {
            super(message);
        }
    }
}
