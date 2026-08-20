package fan.summer.fengyu.ai.tasks;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fan.summer.fengyu.ai.tools.AiPermissionMode;
import fan.summer.fengyu.ai.workflow.WorkflowWebhookTriggerService;
import fan.summer.fengyu.database.entity.ai.WorkflowScheduleEntity;
import fan.summer.fengyu.database.repository.ai.WorkflowScheduleRepository;
import fan.summer.fengyu.security.SecurityContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

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
        WorkflowScheduleRepository repository = repository();
        SecurityContext security = mock(SecurityContext.class);
        when(workflowProvider.getIfAvailable()).thenReturn(workflows);
        when(security.currentUserId()).thenReturn(1L);
        return new BackgroundTaskScheduler(tasks, executions, workflowProvider,
                repository, security, false);
    }

    private static WorkflowScheduleRepository repository() {
        WorkflowScheduleRepository repository = mock(WorkflowScheduleRepository.class);
        when(repository.save(any(WorkflowScheduleEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findByClaimedAtIsNotNull()).thenReturn(List.of());
        when(repository.findByStatusOrderByCreatedAtAsc("ACTIVE")).thenReturn(List.of());
        return repository;
    }

    private static fan.summer.fengyu.ai.workflow.WorkflowService anyWorkflowService() {
        fan.summer.fengyu.ai.workflow.WorkflowService workflows =
                mock(fan.summer.fengyu.ai.workflow.WorkflowService.class);
        when(workflows.get("wf-1")).thenReturn(new fan.summer.fengyu.ai.workflow.WorkflowDefinition(
                "wf-1", "n", "d", Map.of(), null, Map.of(), Map.of(), true, 1, null, null));
        when(workflows.get("missing")).thenThrow(new IllegalArgumentException("Unknown workflow: missing"));
        when(workflows.compile(eq("missing"), any(), eq(true)))
                .thenThrow(new IllegalArgumentException("Unknown workflow: missing"));
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
    void overdueRecurringScheduleCoalescesMissedIntervalsWithoutClockDrift() {
        BackgroundTaskScheduler scheduler =
                scheduler(new BackgroundTaskRegistry(), anyWorkflowService());
        BackgroundTaskScheduler.Schedule schedule = scheduler.create(
                "wf-1", Map.of(), 60, true, false);
        Instant originalBoundary = Instant.now().minusSeconds(185);
        schedule.nextFireAt = originalBoundary;

        scheduler.tick();

        assertEquals(3, schedule.missedFires);
        assertEquals(originalBoundary.plusSeconds(240), schedule.nextFireAt);
        assertTrue(schedule.nextFireAt.isAfter(Instant.now()));
    }

    @Test
    void pausesARecoveredScheduleWhenPluginIsolationWouldBeWeakened() {
        java.util.concurrent.atomic.AtomicBoolean unsandboxed =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        BackgroundTaskScheduler scheduler = scheduler(
                new BackgroundTaskRegistry(), anyWorkflowService(), unsandboxed::get);
        BackgroundTaskScheduler.Schedule schedule = scheduler.create(
                "wf-1", Map.of(), 60, true, false);
        schedule.nextFireAt = Instant.now().minusSeconds(1);

        unsandboxed.set(true);
        scheduler.tick();

        assertEquals(0, schedule.fires);
        assertTrue(schedule.lastError.contains("re-enable the sandbox"));
        assertTrue(schedule.nextFireAt.isBefore(Instant.now()),
                "the paused occurrence remains due instead of being silently skipped");
    }

    @Test
    void unknownPersistedSandboxProfileFailsClosed() {
        WorkflowScheduleRepository repository = repository();
        WorkflowScheduleEntity recovered = entity("unknown-profile", Instant.now().plusSeconds(3600));
        recovered.setSandboxProfile(null);
        recovered.setNextFireAt(Instant.now().minusSeconds(1));
        when(repository.findByStatusOrderByCreatedAtAsc("ACTIVE")).thenReturn(List.of(recovered));

        BackgroundTaskScheduler scheduler = scheduler(repository, () -> true);
        scheduler.recoverSchedules();
        scheduler.tick();

        Map<String, Object> summary = scheduler.list().getFirst();
        assertEquals("sandboxed", summary.get("sandboxProfile"));
        assertTrue(((String) summary.get("lastError")).contains("re-enable the sandbox"));
    }

    @Test
    void fireForTestSubmitsATaskIntoTheSharedRegistry() throws Exception {
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry();
        BackgroundTaskScheduler scheduler = scheduler(registry, anyWorkflowService());
        BackgroundTaskScheduler.Schedule schedule = scheduler.create("wf-1", Map.of(), 60, true, false);
        scheduler.fireForTest(schedule, task -> "scheduled-result");

        BackgroundTaskRegistry.Task task = registry.get(schedule.lastTaskId());
        assertEquals("workflow-schedule", task.kind());
        assertEquals(BackgroundTaskRegistry.Priority.BATCH, task.priority());
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
    @SuppressWarnings("unchecked")
    void workflowDeletionCancelsWebhookTriggersForTheSameOwner() {
        BackgroundTaskRegistry tasks = new BackgroundTaskRegistry();
        ObjectProvider<fan.summer.fengyu.ai.workflow.WorkflowExecutionService> executions =
                mock(ObjectProvider.class);
        ObjectProvider<fan.summer.fengyu.ai.workflow.WorkflowService> workflowProvider =
                mock(ObjectProvider.class);
        ObjectProvider<WorkflowWebhookTriggerService> webhookProvider = mock(ObjectProvider.class);
        fan.summer.fengyu.ai.workflow.WorkflowService workflows = anyWorkflowService();
        WorkflowWebhookTriggerService webhooks = mock(WorkflowWebhookTriggerService.class);
        WorkflowScheduleRepository repository = repository();
        SecurityContext security = mock(SecurityContext.class);
        when(security.currentUserId()).thenReturn(1L);
        when(workflowProvider.getIfAvailable()).thenReturn(workflows);
        when(webhookProvider.getIfAvailable()).thenReturn(webhooks);
        when(repository.findByWorkflowIdAndUserIdAndStatus("wf-1", 1L, "ACTIVE"))
                .thenReturn(List.of());
        when(webhooks.cancelForWorkflow("wf-1", 1L)).thenReturn(2);
        BackgroundTaskScheduler scheduler = new BackgroundTaskScheduler(tasks, executions,
                workflowProvider, webhookProvider, repository, security);

        BackgroundTaskScheduler.WorkflowDeleteResult result = scheduler.deleteWorkflow("wf-1");

        assertEquals(0, result.cancelledSchedules());
        assertEquals(2, result.cancelledWebhookTriggers());
        verify(webhooks).cancelForWorkflow("wf-1", 1L);
        verify(workflows).delete("wf-1");
    }

    @Test
    void expiredSchedulesAreEvictedOnTick() {
        WorkflowScheduleRepository repository = repository();
        WorkflowScheduleEntity expired = entity("expired", Instant.now().minusSeconds(1));
        when(repository.findByStatusOrderByCreatedAtAsc("ACTIVE")).thenReturn(List.of(expired));
        BackgroundTaskScheduler scheduler = scheduler(repository);
        scheduler.recoverSchedules();

        assertEquals(List.of(), scheduler.list());
        assertEquals("EXPIRED", expired.getStatus());
        verify(repository).save(expired);
    }

    @Test
    void restartRestoresActiveSchedulesAndMarksInterruptedClaimWithoutReplay() {
        WorkflowScheduleRepository repository = repository();
        WorkflowScheduleEntity recovered = entity("recover-me", Instant.now().plusSeconds(3600));
        recovered.setClaimedAt(Instant.now().minusSeconds(10));
        when(repository.findByClaimedAtIsNotNull()).thenReturn(List.of(recovered));
        when(repository.findByStatusOrderByCreatedAtAsc("ACTIVE")).thenReturn(List.of(recovered));
        BackgroundTaskScheduler scheduler = scheduler(repository);

        scheduler.recoverSchedules();

        assertEquals(1, scheduler.list().size());
        assertEquals("recover-me", scheduler.list().getFirst().get("scheduleId"));
        assertEquals(null, recovered.getClaimedAt());
        assertTrue(recovered.getLastError().contains("not replayed"));
        scheduler.stopTicker();
    }

    @SuppressWarnings("unchecked")
    private static BackgroundTaskScheduler scheduler(WorkflowScheduleRepository repository) {
        return scheduler(repository, () -> false);
    }

    @SuppressWarnings("unchecked")
    private static BackgroundTaskScheduler scheduler(WorkflowScheduleRepository repository,
                                                      java.util.function.BooleanSupplier unsandboxedPlugins) {
        ObjectProvider<fan.summer.fengyu.ai.workflow.WorkflowExecutionService> executions = mock(ObjectProvider.class);
        ObjectProvider<fan.summer.fengyu.ai.workflow.WorkflowService> workflows = mock(ObjectProvider.class);
        SecurityContext security = mock(SecurityContext.class);
        when(security.currentUserId()).thenReturn(1L);
        return new BackgroundTaskScheduler(new BackgroundTaskRegistry(), executions, workflows,
                repository, security, false, unsandboxedPlugins);
    }

    @SuppressWarnings("unchecked")
    private static BackgroundTaskScheduler scheduler(
            BackgroundTaskRegistry tasks,
            fan.summer.fengyu.ai.workflow.WorkflowService workflows,
            java.util.function.BooleanSupplier unsandboxedPlugins) {
        ObjectProvider<fan.summer.fengyu.ai.workflow.WorkflowExecutionService> executions =
                mock(ObjectProvider.class);
        ObjectProvider<fan.summer.fengyu.ai.workflow.WorkflowService> workflowProvider =
                mock(ObjectProvider.class);
        when(workflowProvider.getIfAvailable()).thenReturn(workflows);
        WorkflowScheduleRepository repository = repository();
        SecurityContext security = mock(SecurityContext.class);
        when(security.currentUserId()).thenReturn(1L);
        return new BackgroundTaskScheduler(tasks, executions, workflowProvider, repository,
                security, false, unsandboxedPlugins);
    }

    private static WorkflowScheduleEntity entity(String id, Instant expiresAt) {
        WorkflowScheduleEntity entity = new WorkflowScheduleEntity();
        entity.setId(id);
        entity.setUserId(1L);
        entity.setWorkflowId("wf-1");
        entity.setInputsJson("{}");
        entity.setIntervalSeconds(60);
        entity.setRecurring(true);
        entity.setPermissionMode(AiPermissionMode.ASK_FOR_APPROVAL.name());
        entity.setSandboxProfile("sandboxed");
        entity.setStatus("ACTIVE");
        entity.setCreatedAt(Instant.now().minusSeconds(60));
        entity.setExpiresAt(expiresAt);
        entity.setNextFireAt(Instant.now().plusSeconds(60));
        return entity;
    }
}
