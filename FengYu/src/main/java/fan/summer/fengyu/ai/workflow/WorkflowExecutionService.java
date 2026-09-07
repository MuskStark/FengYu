package fan.summer.fengyu.ai.workflow;

import fan.summer.fengyu.ai.agent.AgentEventSink;
import fan.summer.fengyu.ai.agent.AgentPlan;
import fan.summer.fengyu.ai.agent.AgentRun;
import fan.summer.fengyu.ai.agent.AgentRunConfig;
import fan.summer.fengyu.ai.agent.AgentRunPersistenceService;
import fan.summer.fengyu.ai.agent.AgentRunRegistry;
import fan.summer.fengyu.ai.agent.AgentRunner;
import fan.summer.fengyu.ai.tools.AiPermissionContext;
import fan.summer.fengyu.ai.tools.AiPermissionMode;
import fan.summer.fengyu.notification.NotificationService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One execution entry point shared by manual REST runs and AI workflow tools. */
@Service
public class WorkflowExecutionService {
    private static final long AI_RUN_TIMEOUT_MINUTES = 15;
    /**
     * How long a completed run's waiter stays reachable after its future settled. The
     * synchronous callers reach {@link #waitForAiRun} microseconds after {@link #startForAi}
     * on the same thread, so this only bounds the leak of an entry whose caller never
     * arrives (an exception between the two calls) while still mailboxing fast runs that
     * complete before their caller gets there.
     */
    private static final long WAITER_RETENTION_SECONDS = 120;
    private final WorkflowService workflows;
    private final AgentRunRegistry registry;
    private final AgentRunPersistenceService persistence;
    private final AgentRunner runner;
    /** Optional: host notifications when an unattended run pauses at an approval gate. */
    private final org.springframework.beans.factory.ObjectProvider<NotificationService> notifications;

    public WorkflowExecutionService(WorkflowService workflows, AgentRunRegistry registry,
                                    AgentRunPersistenceService persistence, AgentRunner runner) {
        this(workflows, registry, persistence, runner, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WorkflowExecutionService(WorkflowService workflows, AgentRunRegistry registry,
                                    AgentRunPersistenceService persistence, AgentRunner runner,
                                    org.springframework.beans.factory.ObjectProvider<NotificationService> notifications) {
        this.workflows = workflows;
        this.registry = registry;
        this.persistence = persistence;
        this.runner = runner;
        this.notifications = notifications;
    }

    public AgentRun createManual(String workflowId, Map<String, Object> inputs,
                                 AgentRunConfig config) {
        return createManual(workflowId, inputs, config, Map.of());
    }

    /** As above, attaching the run's file-class input grants for {@code @file:<name>} binding. */
    public AgentRun createManual(String workflowId, Map<String, Object> inputs,
                                 AgentRunConfig config,
                                 Map<String, List<fan.summer.fengyu.ai.ChatFileContext.ActiveFileRef>> fileRefs) {
        WorkflowDefinition definition = workflows.get(workflowId);
        AgentPlan plan = workflows.compile(workflowId, inputs, false);
        AgentRunConfig effective = config == null
                ? new AgentRunConfig(false, true, false, 0, AiPermissionMode.ASK_FOR_APPROVAL)
                : config;
        AgentRun run = registry.create(definition.name(), effective, plan);
        run.attachFileRefs(fileRefs);
        return run;
    }

    /** Execute a published workflow as one synchronous Spring AI tool call. */
    public String executeForAi(String workflowId, Map<String, Object> inputs) {
        return executeForAi(workflowId, inputs, true);
    }

    /**
     * As above, optionally admitting DRAFT workflows: the chat-bound {@code run_current_flow}
     * tool (the flow builder's Flowise-style chat panel) converses with the flow being edited,
     * which need not be published yet. Everything else — permission inheritance, approval
     * gates, history persistence — is identical to the published path.
     */
    public String executeForAi(String workflowId, Map<String, Object> inputs, boolean requirePublished) {
        AgentRun run = startForAi(workflowId, inputs, requirePublished);
        return waitForAiRun(run);
    }

    /**
     * Starts the AI workflow execution and returns the live run immediately — the
     * asynchronous entry used by the background-task registry, whose canceller flips the
     * run's cancellation flag so a killed task stops at the next cooperative checkpoint.
     */
    public AgentRun startForAi(String workflowId, Map<String, Object> inputs) {
        return startForAi(workflowId, inputs, true);
    }

    public AgentRun startForAi(String workflowId, Map<String, Object> inputs, boolean requirePublished) {
        try {
            WorkflowDefinition definition = workflows.get(workflowId);
            AgentPlan plan = workflows.compile(workflowId, inputs, requirePublished);
            // AI-invoked workflows inherit the INVOKING context's permission mode — never a
            // hardcoded FULL_ACCESS. Rules and hooks then evaluate every step exactly as if
            // the model had called the step's tool directly in chat, and unmatched
            // external-effect steps pause for approval instead of running unsandboxed.
            // Background task/schedule callers restore the submitting chat's mode around
            // this call (BackgroundTaskTools / BackgroundTaskScheduler); unbound callers
            // get the ASK_FOR_APPROVAL default.
            AgentRun run = registry.create(definition.name(),
                    new AgentRunConfig(false, false, false, 0, AiPermissionContext.current()), plan);
            // No stream client follows this run (schedules, webhooks, AI background tasks):
            // the runner fails unanswered approval gates on a short timeout instead of
            // blocking a task slot for the full workflow timeout.
            run.markUnattended();
            persistence.create(run, null);
            AgentEventSink sink = persistence.persisting(run, aiSink(run));
            NotificationService notify = notifications == null
                    ? null : notifications.getIfAvailable();
            if (notify != null) {
                sink = approvalNotifying(run, notify, sink);
            }
            runner.run(run, sink);
            return run;
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            throw new IllegalStateException("Workflow execution failed: " + cause.getMessage(), cause);
        }
    }

    /** Blocks for the run's result with the shared AI timeout; cancels the run on timeout. */
    public String waitForAiRun(AgentRun run) {
        AiRunWaiter waiter = waiters.remove(run.getRunId());
        CompletableFuture<String> result = waiter == null ? new CompletableFuture<>() : waiter.future();
        if (waiter == null) {
            result.completeExceptionally(new IllegalStateException(
                    "No waiter registered for run " + run.getRunId()));
        }
        try {
            return result.get(AI_RUN_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (TimeoutException timeout) {
            run.markCancelled();
            run.approve(null);
            registry.remove(run.getRunId());
            throw new IllegalStateException("Workflow execution timed out after "
                    + AI_RUN_TIMEOUT_MINUTES + " minutes");
        } catch (Exception error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            throw new IllegalStateException("Workflow execution failed: "
                    + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()), cause);
        }
    }

    private final Map<String, AiRunWaiter> waiters = new ConcurrentHashMap<>();
    private record AiRunWaiter(CompletableFuture<String> future) {}

    /** Builds the completing sink for an AI-invoked run and registers its waiter. */
    private AgentEventSink aiSink(AgentRun run) {
        CompletableFuture<String> result = new CompletableFuture<>();
        Map<Integer, String> stepResults = new ConcurrentHashMap<>();
        AiRunWaiter waiter = new AiRunWaiter(result);
        waiters.put(run.getRunId(), waiter);
        // NOTE: the map is the MAILBOX for waitForAiRun — a run that completes before its
        // caller arrives (the synchronous executeForAi path) must keep its entry until
        // waitForAiRun consumes it. An entry whose caller NEVER arrives (an exception
        // between startForAi and waitForAiRun) is reclaimed after WAITER_RETENTION_SECONDS,
        // bounding the residual leak instead of letting the map grow until restart.
        result.whenComplete((ignored, error) -> {
            registry.remove(run.getRunId());
            CompletableFuture.delayedExecutor(WAITER_RETENTION_SECONDS, TimeUnit.SECONDS)
                    .execute(() -> waiters.remove(run.getRunId(), waiter));
        });
        return new AgentEventSink() {
            @Override public void onPlanToken(String delta) { }
            @Override public void onPlanReady(AgentPlan ready) { }
            @Override public void onPlanApprovalRequested() { }
            @Override public void onStepStart(int index) { }
            @Override public void onStepComplete(int index, String value) {
                stepResults.put(index, value == null ? "" : value);
            }
            @Override public void onStepApprovalRequested(int index) { }
            @Override public void onComplete(String summary) {
                // A branch may skip the numerically last plan step. Return the last step that
                // actually completed so AI callers receive the chosen branch's real result
                // instead of a generic run summary.
                String finalStep = stepResults.entrySet().stream()
                        .max(Map.Entry.comparingByKey())
                        .map(Map.Entry::getValue)
                        .orElse(null);
                result.complete(finalStep == null || finalStep.isEmpty() ? summary : finalStep);
            }
            @Override public void onError(String message) {
                result.completeExceptionally(new IllegalStateException(message));
            }
        };
    }

    /**
     * Wraps the run's sink so a pause at an approval gate emits a host notification
     * (toast + native desktop notification + history) carrying the runId — the only channel
     * an owner of a schedule/webhook-triggered run has to learn the gate is waiting, since
     * no stream client is attached. Mirrors the controller path's {@code forAgentRun} usage;
     * notification failures never break the run's real event stream.
     */
    private AgentEventSink approvalNotifying(AgentRun run, NotificationService notifications,
                                             AgentEventSink delegate) {
        return new AgentEventSink() {
            @Override public void onPlanToken(String delta) { delegate.onPlanToken(delta); }
            @Override public void onPlanReady(AgentPlan ready) { delegate.onPlanReady(ready); }
            @Override public void onPlanApprovalRequested() { delegate.onPlanApprovalRequested(); }
            @Override public void onPlanApprovalRequested(String gateId) {
                delegate.onPlanApprovalRequested(gateId);
                notifyApprovalGate("plan");
            }
            @Override public void onStepStart(int index) { delegate.onStepStart(index); }
            @Override public void onStepComplete(int index, String result) {
                delegate.onStepComplete(index, result);
            }
            @Override public void onStepRetry(int index, int nextAttempt, int maxAttempts,
                                              long delayMs, String error) {
                delegate.onStepRetry(index, nextAttempt, maxAttempts, delayMs, error);
            }
            @Override public void onStepSkipped(int index) { delegate.onStepSkipped(index); }
            @Override public void onStepApprovalRequested(int index) {
                delegate.onStepApprovalRequested(index);
            }
            @Override public void onStepApprovalRequested(int index, String gateId) {
                delegate.onStepApprovalRequested(index, gateId);
                notifyApprovalGate("step " + index);
            }
            @Override public void onComplete(String summary) { delegate.onComplete(summary); }
            @Override public void onError(String message) { delegate.onError(message); }

            private void notifyApprovalGate(String gate) {
                try {
                    String goal = run.getGoal() == null ? "" : run.getGoal();
                    String body = goal.length() <= 200 ? goal : goal.substring(0, 200) + "…";
                    notifications.create("agent", "warning", "Agent run awaiting approval",
                            body + "\n" + gate + " approval is pending (runId: "
                                    + run.getRunId() + "); approve it in the Agent view "
                                    + "before it times out", "/agent");
                } catch (RuntimeException ignored) {
                    // Notification delivery must never stall or fail the run.
                }
            }
        };
    }
}
