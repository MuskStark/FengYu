package fan.summer.fengyu.notification;

import fan.summer.fengyu.ai.agent.AgentEventSink;
import fan.summer.fengyu.ai.agent.AgentPlan;
import fan.summer.fengyu.ai.agent.AgentRun;
import fan.summer.fengyu.ai.agent.AgentRunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps an agent run's real {@link AgentEventSink} and emits a host notification when the
 * run reaches a terminal state — the "long-running background work finished while you were
 * elsewhere" case the unified notification surface exists for.
 *
 * <p>Success notifies with level {@code success}; failure with {@code error}. A run the user
 * cancelled themselves ({@link AgentRunStatus#CANCELLED} by the time {@code onError} fires)
 * notifies nothing — the user already knows.
 *
 * <p>The stored title is the English fallback; the frontend localizes known
 * {@code source:"agent"} titles via i18n, exactly like every other known source. The body
 * carries the run's goal (the user's own words — language-neutral by construction).
 *
 * <p>Notification failures (DB down, etc.) are logged and swallowed: they must never break
 * the run's real event stream. Delivery work happens on the runner's virtual thread, and
 * {@link NotificationService#create} is a bounded DB write plus a non-blocking fan-out, so
 * the decorator honors the sink contract of never stalling the orchestration.
 */
final class AgentNotificationSink implements AgentEventSink {

    private static final Logger log = LoggerFactory.getLogger(AgentNotificationSink.class);

    /** Longest goal excerpt carried into the notification body. */
    private static final int MAX_GOAL_EXCERPT = 300;

    private final AgentRun run;
    private final AgentEventSink delegate;
    private final NotificationService notifications;

    AgentNotificationSink(AgentRun run, AgentEventSink delegate, NotificationService notifications) {
        this.run = run;
        this.delegate = delegate;
        this.notifications = notifications;
    }

    @Override public void onPlanToken(String delta) {
        delegate.onPlanToken(delta);
    }

    @Override public void onPlanReady(AgentPlan plan) {
        delegate.onPlanReady(plan);
    }

    @Override public void onPlanApprovalRequested() {
        delegate.onPlanApprovalRequested();
    }

    @Override public void onStepStart(int index) {
        delegate.onStepStart(index);
    }

    @Override public void onStepComplete(int index, String result) {
        delegate.onStepComplete(index, result);
    }

    @Override public void onStepRetry(int index, int nextAttempt, int maxAttempts,
                                      long delayMs, String error) {
        delegate.onStepRetry(index, nextAttempt, maxAttempts, delayMs, error);
    }

    @Override public void onStepSkipped(int index) {
        delegate.onStepSkipped(index);
    }

    @Override public void onStepApprovalRequested(int index) {
        delegate.onStepApprovalRequested(index);
    }

    @Override public void onComplete(String summary) {
        delegate.onComplete(summary);
        emit("success", "Agent run completed");
    }

    @Override public void onError(String message) {
        delegate.onError(message);
        if (run.getStatus() == AgentRunStatus.CANCELLED) return;
        emit("error", "Agent run failed");
    }

    private void emit(String level, String fallbackTitle) {
        try {
            String goal = run.getGoal() == null ? "" : run.getGoal();
            String body = goal.length() <= MAX_GOAL_EXCERPT ? goal
                    : goal.substring(0, MAX_GOAL_EXCERPT) + "…";
            notifications.create("agent", level, fallbackTitle, body, "/agent");
        } catch (RuntimeException e) {
            log.warn("agent {}: terminal notification not delivered: {}", run.getRunId(), e.getMessage());
        }
    }
}
