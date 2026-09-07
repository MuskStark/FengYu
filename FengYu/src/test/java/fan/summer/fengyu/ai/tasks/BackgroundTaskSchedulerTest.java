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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
    void rejectsFirstFireAtOrAfterExpiryButAllowsAnImmediateFire() {
        BackgroundTaskScheduler scheduler =
                scheduler(new BackgroundTaskRegistry(), anyWorkflowService());
        int expirySeconds = BackgroundTaskScheduler.EXPIRY_DAYS * 24 * 3600;
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.create("wf-1", Map.of(), expirySeconds, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.create("wf-1", Map.of(), expirySeconds + 1, true, false));
        BackgroundTaskScheduler.Schedule delayed = scheduler.create(
                "wf-1", Map.of(), expirySeconds - 1, false, false);
        assertTrue(delayed.nextFireAt.isBefore(delayed.expiresAt));
        BackgroundTaskScheduler.Schedule immediate = scheduler.create(
                "wf-1", Map.of(), expirySeconds, false, true);
        assertEquals(immediate.createdAt, immediate.nextFireAt);
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

    /**
     * P2-8: a one-shot occurrence is durably claimed as COMPLETED <em>before</em> its task is
     * submitted; a submission failure (here: no execution service; in production typically
     * queue capacity) must flip the terminal state to FAILED — the database must never
     * record an occurrence that never ran as a success.
     */
    @Test
    void oneShotSubmissionFailureRecordsFailedInsteadOfCompleted() {
        BackgroundTaskScheduler scheduler =
                scheduler(new BackgroundTaskRegistry(), anyWorkflowService());
        BackgroundTaskScheduler.Schedule once = scheduler.create(
                "wf-1", Map.of(), 60, false, false);
        once.nextFireAt = Instant.now().minusSeconds(1);

        scheduler.tick();

        assertFalse(scheduler.list().stream()
                .anyMatch(s -> s.get("scheduleId").equals(once.id)), "one-shot removed after fire");
        assertEquals("FAILED", once.status,
                "a submission failure is a terminal FAILED, not a phantom COMPLETED");
        assertTrue(once.lastError != null && once.lastError.contains("Workflow execution unavailable"),
                "the failure reason is recorded: " + once.lastError);
        assertEquals(0, once.fires, "nothing actually ran");
    }

    /**
     * P1-2 create path: under the ask-for-approval default a schedule with an uncovered
     * non-read step could never clear its approval gate (nobody watches a scheduled run), so
     * creation is rejected with an actionable message; an explicit non-ask mode is accepted.
     */
    @Test
    @SuppressWarnings("unchecked")
    void createRejectsAskModeScheduleWhoseWorkflowWouldPauseUnattended() {
        ObjectProvider<fan.summer.fengyu.ai.workflow.WorkflowExecutionService> executions =
                mock(ObjectProvider.class);
        ObjectProvider<fan.summer.fengyu.ai.workflow.WorkflowService> workflowProvider =
                mock(ObjectProvider.class);
        fan.summer.fengyu.ai.workflow.WorkflowService workflows = anyWorkflowService();
        when(workflows.compile(eq("wf-1"), any(), eq(true))).thenReturn(new fan.summer.fengyu.ai.agent.AgentPlan(
                "mutating flow",
                List.of(new fan.summer.fengyu.ai.agent.AgentStep(
                        0, "mutate", Map.of(), "writes", false)),
                ""));
        when(workflowProvider.getIfAvailable()).thenReturn(workflows);
        ObjectProvider<fan.summer.fengyu.ai.agent.UnattendedTriggerPolicy> policies =
                mock(ObjectProvider.class);
        when(policies.getIfAvailable()).thenReturn(new fan.summer.fengyu.ai.agent.UnattendedTriggerPolicy(
                null, () -> List.of(new AuditedWriteTool())));
        SecurityContext security = mock(SecurityContext.class);
        when(security.currentUserId()).thenReturn(1L);
        BackgroundTaskScheduler scheduler = new BackgroundTaskScheduler(
                new BackgroundTaskRegistry(), executions, workflowProvider, repository(),
                security, () -> false, policies);

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> scheduler.create("wf-1", Map.of(), 120, true, false));
        assertTrue(rejected.getMessage().contains("ask-for-approval"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("permission rule"), rejected.getMessage());
        assertEquals(List.of(), scheduler.list(), "nothing was persisted by the rejected create");

        // An explicit non-ask mode answers its own gates: creation succeeds.
        assertDoesNotThrow(() -> scheduler.create(
                "wf-1", Map.of(), 120, true, false, null, AiPermissionMode.FULL_ACCESS));
        assertEquals(1, scheduler.list().size());
    }

    /** A write-effect audited callback — the dimension {@code UnattendedTriggerPolicy} screens on. */
    static final class AuditedWriteTool implements org.springframework.ai.tool.ToolCallback,
            fan.summer.fengyu.ai.tools.AuditedToolCallback {
        @Override public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return org.springframework.ai.tool.definition.DefaultToolDefinition.builder()
                    .name("mutate").description("mutates").inputSchema("{}").build();
        }
        @Override public String call(String toolInput) { return "ok"; }
        @Override public fan.summer.fengyu.ai.tools.ToolEffect effect() {
            return fan.summer.fengyu.ai.tools.ToolEffect.WRITE;
        }
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
                workflowProvider, webhookProvider, repository, security, null);

        BackgroundTaskScheduler.WorkflowDeleteResult result = scheduler.deleteWorkflow("wf-1");

        assertEquals(0, result.cancelledSchedules());
        assertEquals(2, result.cancelledWebhookTriggers());
        verify(webhooks).cancelForWorkflow("wf-1", 1L);
        verify(workflows).delete("wf-1");
    }

    /**
     * A6 regression: with a transaction open, the in-memory schedule is only REMOVED at
     * afterCommit — between deleteWorkflow's monitor release and that commit, a concurrent
     * tick() still sees the schedule and must not fire a to-be-deleted occurrence. The
     * in-memory status must flip synchronously inside deleteWorkflow.
     */
    @Test
    @SuppressWarnings("unchecked")
    void deletedWorkflowSchedulesStopFiringBeforeTheTransactionCommits() {
        BackgroundTaskRegistry tasks = new BackgroundTaskRegistry();
        ObjectProvider<fan.summer.fengyu.ai.workflow.WorkflowExecutionService> executions =
                mock(ObjectProvider.class);
        ObjectProvider<fan.summer.fengyu.ai.workflow.WorkflowService> workflowProvider =
                mock(ObjectProvider.class);
        WorkflowScheduleRepository repository = repository();
        SecurityContext security = mock(SecurityContext.class);
        when(security.currentUserId()).thenReturn(1L);
        // Materialize the workflow mock BEFORE the provider stubbing: anyWorkflowService()
        // stubs mocks itself, which Mockito rejects inside an unfinished when(...).
        fan.summer.fengyu.ai.workflow.WorkflowService workflows = anyWorkflowService();
        when(workflowProvider.getIfAvailable()).thenReturn(workflows);
        BackgroundTaskScheduler scheduler = new BackgroundTaskScheduler(tasks, executions,
                workflowProvider, repository, security, false);

        BackgroundTaskScheduler.Schedule schedule = scheduler.create("wf-1", Map.of(), 60, true, false);
        schedule.nextFireAt = Instant.now().minusSeconds(1);   // due right now
        WorkflowScheduleEntity cancelling = entity(schedule.id, Instant.now().plusSeconds(3600));
        when(repository.findByWorkflowIdAndUserIdAndStatus("wf-1", 1L, "ACTIVE"))
                .thenReturn(List.of(cancelling));
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();

        try {
            scheduler.deleteWorkflow("wf-1");
            assertEquals("CANCELLED", schedule.status,
                    "the in-memory twin flips synchronously, not at commit");
            scheduler.tick();                                    // what a racing ticker does
            // Without the synchronous flip, tick would claim this due occurrence (advancing
            // nextFireAt) and record a fire failure — the to-be-deleted schedule must stay
            // completely untouched until the afterCommit removal.
            assertEquals(null, schedule.lastError, "a to-be-deleted occurrence must not fire");
            assertTrue(schedule.nextFireAt.isBefore(Instant.now()),
                    "the due occurrence must not be claimed");
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
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
