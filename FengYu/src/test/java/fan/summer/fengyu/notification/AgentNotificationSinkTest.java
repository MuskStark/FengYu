package fan.summer.fengyu.notification;

import fan.summer.fengyu.ai.agent.AgentEventSink;
import fan.summer.fengyu.ai.agent.AgentPlan;
import fan.summer.fengyu.ai.agent.AgentRun;
import fan.summer.fengyu.ai.agent.AgentRunStatus;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The agent terminal-event decorator: transparent forwarding for every non-terminal event,
 * exactly one notification on complete/error, none for a user-cancelled run, and a
 * notification failure that never breaks the real stream.
 */
class AgentNotificationSinkTest {

    private final NotificationService notifications = mock(NotificationService.class);
    private final RecordingSink delegate = new RecordingSink();

    private static AgentRun runWithStatus(AgentRunStatus status) {
        AgentRun run = mock(AgentRun.class);
        when(run.getRunId()).thenReturn("run-1");
        when(run.getGoal()).thenReturn("Summarize the quarterly report");
        when(run.getStatus()).thenReturn(status);
        return run;
    }

    @Test
    void forwardsNonTerminalEventsUntouched() {
        AgentNotificationSink sink = new AgentNotificationSink(
                runWithStatus(AgentRunStatus.EXECUTING), delegate, notifications);

        sink.onPlanToken("to");
        sink.onPlanToken("ken");
        sink.onPlanReady(mock(AgentPlan.class));
        sink.onPlanApprovalRequested();
        sink.onStepStart(0);
        sink.onStepComplete(0, "result");
        sink.onStepApprovalRequested(1);

        assertEquals(2, delegate.planTokens);
        assertEquals(1, delegate.planReady);
        assertEquals(1, delegate.planApproval);
        assertEquals(1, delegate.stepStarts);
        assertEquals(1, delegate.stepCompletes);
        assertEquals(1, delegate.stepApprovals);
        verifyNoInteractions(notifications);
    }

    @Test
    void completeNotifiesSuccess() {
        AgentNotificationSink sink = new AgentNotificationSink(
                runWithStatus(AgentRunStatus.COMPLETED), delegate, notifications);

        sink.onComplete("all done");

        assertEquals(1, delegate.completes);
        verify(notifications).create(
                eq("agent"), eq("success"), eq("Agent run completed"),
                eq("Summarize the quarterly report"), eq("/agent"));
    }

    @Test
    void errorNotifiesFailure() {
        AgentNotificationSink sink = new AgentNotificationSink(
                runWithStatus(AgentRunStatus.FAILED), delegate, notifications);

        sink.onError("tool exploded");

        assertEquals(1, delegate.errors);
        verify(notifications).create(
                eq("agent"), eq("error"), eq("Agent run failed"),
                eq("Summarize the quarterly report"), eq("/agent"));
    }

    @Test
    void cancelledRunDoesNotNotify() {
        AgentNotificationSink sink = new AgentNotificationSink(
                runWithStatus(AgentRunStatus.CANCELLED), delegate, notifications);

        sink.onError("Run cancelled");

        assertEquals(1, delegate.errors);
        verifyNoInteractions(notifications);
    }

    @Test
    void notificationFailureNeverBreaksTheRealStream() {
        when(notifications.create(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("db down"));
        AgentNotificationSink sink = new AgentNotificationSink(
                runWithStatus(AgentRunStatus.COMPLETED), delegate, notifications);

        sink.onComplete("done anyway");

        assertEquals(1, delegate.completes);
    }

    @Test
    void delegateExceptionsStillPropagate() {
        RecordingSink exploding = new RecordingSink() {
            @Override public void onStepComplete(int index, String result) {
                throw new IllegalStateException("stream died");
            }
        };
        AgentNotificationSink sink = new AgentNotificationSink(
                runWithStatus(AgentRunStatus.EXECUTING), exploding, notifications);

        assertThrows(IllegalStateException.class, () -> sink.onStepComplete(0, "r"));
    }

    /** Counts every callback so forwarding can be asserted without a mocking recorder. */
    private static class RecordingSink implements AgentEventSink {
        int planTokens;
        int planReady;
        int planApproval;
        int stepStarts;
        int stepCompletes;
        int stepApprovals;
        int completes;
        int errors;

        @Override public void onPlanToken(String delta) { planTokens++; }
        @Override public void onPlanReady(AgentPlan plan) { planReady++; }
        @Override public void onPlanApprovalRequested() { planApproval++; }
        @Override public void onStepStart(int index) { stepStarts++; }
        @Override public void onStepComplete(int index, String result) { stepCompletes++; }
        @Override public void onStepApprovalRequested(int index) { stepApprovals++; }
        @Override public void onComplete(String summary) { completes++; }
        @Override public void onError(String message) { errors++; }
    }
}
