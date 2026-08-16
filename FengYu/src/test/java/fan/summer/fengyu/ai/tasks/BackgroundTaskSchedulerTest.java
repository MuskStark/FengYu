package fan.summer.fengyu.ai.tasks;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The recurring-workflow scheduler: interval/cap validation, due-fire behavior
 * (recurring reschedules, one-shots remove), and 7-day expiry.
 */
class BackgroundTaskSchedulerTest {

    @SuppressWarnings("unchecked")
    private static BackgroundTaskScheduler scheduler(BackgroundTaskRegistry tasks,
                                                     fan.summer.fengyu.ai.workflow.WorkflowService workflows) {
        ObjectProvider<fan.summer.fengyu.ai.workflow.WorkflowExecutionService> executions = mock(ObjectProvider.class);
        ObjectProvider<fan.summer.fengyu.ai.workflow.WorkflowService> workflowProvider = mock(ObjectProvider.class);
        when(workflowProvider.getIfAvailable()).thenReturn(workflows);
        return new BackgroundTaskScheduler(tasks, executions, workflowProvider);
    }

    private static fan.summer.fengyu.ai.workflow.WorkflowService anyWorkflowService() {
        fan.summer.fengyu.ai.workflow.WorkflowService workflows =
                mock(fan.summer.fengyu.ai.workflow.WorkflowService.class);
        when(workflows.get("wf-1")).thenReturn(new fan.summer.fengyu.ai.workflow.WorkflowDefinition(
                "wf-1", "n", "d", Map.of(), null, Map.of(), Map.of(), true, 1, null, null));
        when(workflows.get("missing")).thenThrow(new IllegalArgumentException("Unknown workflow: missing"));
        return workflows;
    }

    @Test
    void rejectsIntervalsBelowTheFloorAndUnknownWorkflows() {
        BackgroundTaskScheduler scheduler =
                scheduler(new BackgroundTaskRegistry(), anyWorkflowService());
        IllegalArgumentException tooSmall = assertThrows(IllegalArgumentException.class,
                () -> scheduler.create("wf-1", Map.of(), 30, true, false));
        assertTrue(tooSmall.getMessage().contains("60"));
        assertThrows(Exception.class,
                () -> scheduler.create("missing", Map.of(), 120, true, false));
    }

    @Test
    void recurringScheduleRefiresAndOneShotRemoves() {
        BackgroundTaskScheduler scheduler =
                scheduler(new BackgroundTaskRegistry(), anyWorkflowService());
        BackgroundTaskScheduler.Schedule recurring = scheduler.create(
                "wf-1", Map.of(), 60, true, false);
        BackgroundTaskScheduler.Schedule once = scheduler.create(
                "wf-1", Map.of(), 60, false, false);

        // Force both due, tick, and inspect via the list summaries. The execution
        // provider is absent in this test, so firing records an error — but the
        // recurrence bookkeeping (reschedule / one-shot removal) must still happen.
        recurring.nextFireAt = java.time.Instant.now().minusSeconds(1);
        once.nextFireAt = java.time.Instant.now().minusSeconds(1);
        scheduler.tick();
        assertTrue(scheduler.list().stream()
                .anyMatch(s -> s.get("scheduleId").equals(recurring.id)),
                "recurring schedule stays for its next fire");
        assertFalse(scheduler.list().stream()
                .anyMatch(s -> s.get("scheduleId").equals(once.id)), "one-shot removed after fire");
        assertTrue(recurring.nextFireAt.isAfter(java.time.Instant.now()));

        assertEquals(0, recurring.fires, "no task without an execution service");
        assertTrue(recurring.lastError != null, "the missed fire is recorded");
    }

    @Test
    void fireForTestSubmitsATaskIntoTheSharedRegistry() throws Exception {
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry();
        BackgroundTaskScheduler scheduler = scheduler(registry, anyWorkflowService());
        BackgroundTaskScheduler.Schedule schedule = scheduler.create("wf-1", Map.of(), 60, true, false);
        scheduler.fireForTest(schedule, task -> "scheduled-result");

        BackgroundTaskRegistry.Task task = registry.get(schedule.lastTaskId());
        assertEquals("workflow-schedule", task.kind());
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> task.status() == BackgroundTaskRegistry.Status.COMPLETED);
        assertEquals("scheduled-result", task.output());
        assertEquals(1, schedule.fires);
    }

    @Test
    void deleteStopsAPendingSchedule() {
        BackgroundTaskScheduler scheduler =
                scheduler(new BackgroundTaskRegistry(), anyWorkflowService());
        BackgroundTaskScheduler.Schedule schedule = scheduler.create(
                "wf-1", Map.of(), 60, true, false);
        assertTrue(scheduler.delete(schedule.id));
        assertFalse(scheduler.delete(schedule.id));
        assertEquals(List.of(), scheduler.list());
    }

    @Test
    void expiredSchedulesAreEvictedOnTick() {
        BackgroundTaskScheduler scheduler =
                scheduler(new BackgroundTaskRegistry(), anyWorkflowService());
        BackgroundTaskScheduler.Schedule schedule = scheduler.create(
                "wf-1", Map.of(), 60, true, false);
        // Age the schedule past expiry and force it due; the tick must evict, not fire.
        schedule.nextFireAt = java.time.Instant.now().minusSeconds(1);
        try {
            java.lang.reflect.Field expires = BackgroundTaskScheduler.Schedule.class
                    .getDeclaredField("expiresAt");
            expires.setAccessible(true);
            expires.set(schedule, java.time.Instant.now().minusSeconds(1));
        } catch (ReflectiveOperationException impossible) {
            throw new IllegalStateException(impossible);
        }
        scheduler.tick();
        assertEquals(List.of(), scheduler.list());
        assertEquals(0, schedule.fires);
    }
}
