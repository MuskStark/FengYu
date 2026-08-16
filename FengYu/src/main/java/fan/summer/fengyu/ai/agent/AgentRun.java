package fan.summer.fengyu.ai.agent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

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

    /**
     * Arms the approval gate: installs a fresh count-1 latch so that the next
     * {@link #awaitApproval()} will block, and transitions the run to the given awaiting status
     * ({@link AgentRunStatus#AWAITING_PLAN_APPROVAL} or
     * {@link AgentRunStatus#AWAITING_STEP_APPROVAL}).
     *
     * <p>Called by the AgentRunner when it reaches a synchronization point (plan produced, or a
     * step flagged {@link AgentStep#requiresApproval()}).
     *
     * @param awaitingStatus the status to record while waiting for approval
     */
    public void requestApproval(AgentRunStatus awaitingStatus) {
        this.approvalGate = new CountDownLatch(1);
        this.status = awaitingStatus;
    }

    /**
     * Releases the approval gate (counts the latch down to zero), waking any thread blocked in
     * {@link #awaitApproval()}. Optionally applies an edited plan when the approval was for a
     * plan (a {@code null} argument leaves the current plan unchanged, i.e. simple approval).
     *
     * <p>Called by the controller / user-facing layer to approve (and optionally edit) the plan
     * or step.
     *
     * @param edited the edited plan to install, or {@code null} to keep the current plan
     */
    public void approve(AgentPlan edited) {
        if (edited != null) {
            this.plan = edited;
        }
        this.approvalGate.countDown();
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
}
