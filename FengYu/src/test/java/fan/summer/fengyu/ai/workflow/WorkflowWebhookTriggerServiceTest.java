package fan.summer.fengyu.ai.workflow;

import fan.summer.fengyu.ai.agent.AgentPlan;
import fan.summer.fengyu.ai.agent.AgentRun;
import fan.summer.fengyu.ai.tasks.BackgroundTaskCapacityException;
import fan.summer.fengyu.ai.tasks.BackgroundTaskRegistry;
import fan.summer.fengyu.database.entity.ai.WorkflowWebhookDeliveryEntity;
import fan.summer.fengyu.database.entity.ai.WorkflowWebhookTriggerEntity;
import fan.summer.fengyu.database.repository.ai.WorkflowWebhookDeliveryRepository;
import fan.summer.fengyu.database.repository.ai.WorkflowWebhookTriggerRepository;
import fan.summer.fengyu.security.SecurityContext;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowWebhookTriggerServiceTest {

    @Test
    void secretIsReturnedOnceAndOnlyItsHashIsPersisted() {
        Harness harness = new Harness();

        WorkflowWebhookTriggerService.CreatedTrigger created = harness.service.create(
                "wf-1", "Incoming order", Map.of("region", "east"));

        assertEquals(43, created.secret().length());
        assertEquals("/api/workflow-hooks/" + created.trigger().get("triggerId"),
                created.trigger().get("endpoint"));
        assertFalse(created.trigger().containsKey("secret"));
        assertEquals(List.of("region"), created.trigger().get("defaultInputKeys"));
        assertNotEquals(created.secret(), harness.trigger.get().getSecretHash());
        assertTrue(harness.trigger.get().getSecretHash().matches("[0-9a-f]{64}"));
        assertFalse(harness.service.list().getFirst().containsKey("secret"));
    }

    @Test
    void eventIdIsClaimedBeforeSubmissionAndDuplicatesReuseTheFirstTask() {
        Harness harness = new Harness();
        WorkflowWebhookTriggerService.CreatedTrigger created = harness.service.create(
                "wf-1", "Incoming order", Map.of("region", "east"));
        String triggerId = (String) created.trigger().get("triggerId");

        WorkflowWebhookTriggerService.DeliveryResult first = harness.service.deliver(
                triggerId, created.secret(), "evt-42", Map.of("orderId", 42));
        WorkflowWebhookTriggerService.DeliveryResult duplicate = harness.service.deliver(
                triggerId, created.secret(), "evt-42", Map.of("orderId", 42));

        assertTrue(first.accepted());
        assertFalse(first.duplicate());
        assertEquals("task-1", first.taskId());
        assertTrue(duplicate.accepted());
        assertTrue(duplicate.duplicate());
        assertEquals("task-1", duplicate.taskId());
        verify(harness.tasks, times(1)).submit(eq(1L),
                eq(BackgroundTaskRegistry.Priority.INTERACTIVE), eq("workflow-webhook"),
                eq("webhook Incoming order"), any(BackgroundTaskRegistry.TaskBody.class));
        assertEquals(1, harness.deliveries.size());
        WorkflowWebhookDeliveryEntity stored = harness.deliveries.values().iterator().next();
        assertFalse(stored.getId().contains("evt-42"));
        assertEquals("QUEUED", stored.getStatus());
        assertTrue(stored.getIdempotencyKeyPresent());
    }

    @Test
    void overloadReleasesUnadmittedClaimSoTheSameEventCanRetry() {
        Harness harness = new Harness();
        WorkflowWebhookTriggerService.CreatedTrigger created = harness.service.create(
                "wf-1", "Incoming order", Map.of());
        String triggerId = (String) created.trigger().get("triggerId");
        when(harness.tasks.submit(anyLong(), any(BackgroundTaskRegistry.Priority.class),
                anyString(), anyString(),
                any(BackgroundTaskRegistry.TaskBody.class)))
                .thenThrow(new BackgroundTaskCapacityException(16, 128, 1));

        BackgroundTaskCapacityException overloaded = assertThrows(
                BackgroundTaskCapacityException.class,
                () -> harness.service.deliver(
                        triggerId, created.secret(), "evt-retry", Map.of()));

        assertEquals(1, overloaded.retryAfterSeconds());
        assertTrue(harness.deliveries.isEmpty(),
                "an unadmitted event ID must remain available for retry");

        when(harness.tasks.submit(anyLong(), any(BackgroundTaskRegistry.Priority.class),
                anyString(), anyString(),
                any(BackgroundTaskRegistry.TaskBody.class))).thenReturn(harness.task);
        WorkflowWebhookTriggerService.DeliveryResult retried = harness.service.deliver(
                triggerId, created.secret(), "evt-retry", Map.of());
        assertTrue(retried.accepted());
        assertFalse(retried.duplicate());
        assertEquals("task-1", retried.taskId());
    }

    @Test
    void unkeyedDeliveriesAreDistinctAndVisibleWithoutSensitiveIdentifiers() {
        Harness harness = new Harness();
        WorkflowWebhookTriggerService.CreatedTrigger created = harness.service.create(
                "wf-1", "Incoming order", Map.of());
        String triggerId = (String) created.trigger().get("triggerId");

        harness.service.deliver(triggerId, created.secret(), null, Map.of("orderId", 1));
        harness.service.deliver(triggerId, created.secret(), null, Map.of("orderId", 1));
        List<Map<String, Object>> history = harness.service.listDeliveries(triggerId, 20);

        assertEquals(2, harness.deliveries.size());
        assertEquals(2, history.size());
        assertTrue(history.stream().allMatch(row -> Boolean.FALSE.equals(
                row.get("idempotencyKeyPresent"))));
        assertTrue(history.stream().noneMatch(row -> row.containsKey("eventHash")
                || row.containsKey("payload") || row.containsKey("deliveryId")));
    }

    @Test
    void submittedDeliveryTracksSuccessfulTaskCompletion() throws Exception {
        Harness harness = new Harness();
        AgentRun run = mock(AgentRun.class);
        when(harness.executions.startForAi(eq("wf-1"), anyMap())).thenReturn(run);
        when(harness.executions.waitForAiRun(run)).thenReturn("done");
        WorkflowWebhookTriggerService.CreatedTrigger created = harness.service.create(
                "wf-1", "Incoming order", Map.of());

        harness.service.deliver((String) created.trigger().get("triggerId"), created.secret(),
                "evt-success", Map.of());
        BackgroundTaskRegistry.TaskBody body = submittedBody(harness);
        BackgroundTaskRegistry.Task runningTask = mock(BackgroundTaskRegistry.Task.class);

        assertEquals("done", body.run(runningTask));
        WorkflowWebhookDeliveryEntity stored = harness.deliveries.values().iterator().next();
        assertEquals("COMPLETED", stored.getStatus());
        assertTrue(stored.getCompletedAt() != null);
        assertEquals(null, stored.getError());
    }

    @Test
    void submittedDeliveryTracksTaskFailure() throws Exception {
        Harness harness = new Harness();
        AgentRun run = mock(AgentRun.class);
        when(harness.executions.startForAi(eq("wf-1"), anyMap())).thenReturn(run);
        when(harness.executions.waitForAiRun(run))
                .thenThrow(new IllegalStateException("upstream unavailable"));
        WorkflowWebhookTriggerService.CreatedTrigger created = harness.service.create(
                "wf-1", "Incoming order", Map.of());

        harness.service.deliver((String) created.trigger().get("triggerId"), created.secret(),
                "evt-failure", Map.of());
        BackgroundTaskRegistry.TaskBody body = submittedBody(harness);
        BackgroundTaskRegistry.Task runningTask = mock(BackgroundTaskRegistry.Task.class);

        assertThrows(IllegalStateException.class, () -> body.run(runningTask));
        WorkflowWebhookDeliveryEntity stored = harness.deliveries.values().iterator().next();
        assertEquals("FAILED", stored.getStatus());
        assertTrue(stored.getCompletedAt() != null);
        assertEquals("upstream unavailable", stored.getError());
        assertEquals("upstream unavailable", harness.trigger.get().getLastError());
    }

    @Test
    void submittedDeliveryTracksTaskCancellation() throws Exception {
        Harness harness = new Harness();
        AgentRun run = mock(AgentRun.class);
        when(harness.executions.startForAi(eq("wf-1"), anyMap())).thenReturn(run);
        when(harness.executions.waitForAiRun(run))
                .thenThrow(new IllegalStateException("cancelled by user"));
        WorkflowWebhookTriggerService.CreatedTrigger created = harness.service.create(
                "wf-1", "Incoming order", Map.of());

        harness.service.deliver((String) created.trigger().get("triggerId"), created.secret(),
                "evt-cancelled", Map.of());
        BackgroundTaskRegistry.TaskBody body = submittedBody(harness);
        BackgroundTaskRegistry.Task runningTask = mock(BackgroundTaskRegistry.Task.class);
        when(runningTask.cancelRequested()).thenReturn(false, true);

        assertThrows(IllegalStateException.class, () -> body.run(runningTask));
        WorkflowWebhookDeliveryEntity stored = harness.deliveries.values().iterator().next();
        assertEquals("CANCELLED", stored.getStatus());
        assertTrue(stored.getCompletedAt() != null);
        assertEquals("cancelled by user", stored.getError());
    }

    @Test
    void queuedDeliveryCancellationDoesNotStartTheWorkflow() throws Exception {
        Harness harness = new Harness();
        WorkflowWebhookTriggerService.CreatedTrigger created = harness.service.create(
                "wf-1", "Incoming order", Map.of());

        harness.service.deliver((String) created.trigger().get("triggerId"), created.secret(),
                "evt-queued-cancel", Map.of());
        ArgumentCaptor<Runnable> cancellation = ArgumentCaptor.forClass(Runnable.class);
        verify(harness.task).onCancel(cancellation.capture());
        cancellation.getValue().run();
        BackgroundTaskRegistry.Task runningTask = mock(BackgroundTaskRegistry.Task.class);
        when(runningTask.cancelRequested()).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> submittedBody(harness).run(runningTask));

        WorkflowWebhookDeliveryEntity stored = harness.deliveries.values().iterator().next();
        assertEquals("CANCELLED", stored.getStatus());
        assertTrue(stored.getCompletedAt() != null);
        assertTrue(stored.getError().contains("while queued"));
        verify(harness.executions, times(0)).startForAi(anyString(), anyMap());
    }

    @Test
    void wrongSecretDoesNotRevealWhetherTriggerExists() {
        Harness harness = new Harness();
        WorkflowWebhookTriggerService.CreatedTrigger created = harness.service.create(
                "wf-1", null, Map.of());
        String triggerId = (String) created.trigger().get("triggerId");

        WorkflowWebhookAuthenticationException wrong = assertThrows(
                WorkflowWebhookAuthenticationException.class,
                () -> harness.service.deliver(triggerId, "wrong", null, Map.of()));
        WorkflowWebhookAuthenticationException missing = assertThrows(
                WorkflowWebhookAuthenticationException.class,
                () -> harness.service.deliver("missing", "wrong", null, Map.of()));

        assertEquals(wrong.getMessage(), missing.getMessage());
        verify(harness.tasks, times(0)).submit(anyLong(),
                any(BackgroundTaskRegistry.Priority.class), anyString(), anyString(),
                any(BackgroundTaskRegistry.TaskBody.class));
    }

    @Test
    void sandboxedTriggerFailsClosedIfIsolationIsLaterWeakened() {
        Harness harness = new Harness();
        WorkflowWebhookTriggerService.CreatedTrigger created = harness.service.create(
                "wf-1", null, Map.of());
        harness.unsandboxed.set(true);

        WorkflowWebhookUnavailableException paused = assertThrows(
                WorkflowWebhookUnavailableException.class,
                () -> harness.service.deliver((String) created.trigger().get("triggerId"),
                        created.secret(), "evt-1", Map.of()));

        assertTrue(paused.getMessage().contains("re-enable the sandbox"));
        assertTrue(harness.trigger.get().getLastError().contains("re-enable the sandbox"));
        assertEquals(0, harness.deliveries.size(), "a paused request must not consume its event id");
    }

    @Test
    void startupMarksUnacknowledgedClaimsInterruptedWithoutReplay() {
        Harness harness = new Harness();
        WorkflowWebhookDeliveryEntity claim = new WorkflowWebhookDeliveryEntity();
        claim.setId("hook:hash");
        claim.setTriggerId("hook");
        claim.setEventHash("hash");
        claim.setStatus("CLAIMED");
        claim.setAcceptedAt(java.time.Instant.now());
        when(harness.deliveryRepository.findByStatusInOrderByAcceptedAtAsc(
                List.of("CLAIMED", "QUEUED", "SUBMITTED")))
                .thenReturn(List.of(claim));

        harness.service.recoverInterruptedClaims();

        assertEquals("INTERRUPTED", claim.getStatus());
        assertTrue(claim.getCompletedAt() != null);
        assertTrue(claim.getError().contains("not replayed"));
        verify(harness.deliveryRepository).saveAll(List.of(claim));
        verify(harness.tasks, times(0)).submit(anyLong(),
                any(BackgroundTaskRegistry.Priority.class), anyString(), anyString(),
                any(BackgroundTaskRegistry.TaskBody.class));
    }

    @Test
    void rejectsEphemeralFileInputsThatCannotSurviveAWebhookSession() {
        Harness harness = new Harness();
        when(harness.workflows.get("wf-1")).thenReturn(new WorkflowDefinition(
                "wf-1", "Flow", "",
                Map.of("type", "object", "properties", Map.of(
                        "source", Map.of("type", "string", "format", "fengyu-file"))),
                null, Map.of(), Map.of(), true, 1, null, null));

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> harness.service.create("wf-1", null, Map.of("source", "@file:source")));

        assertTrue(rejected.getMessage().contains("ephemeral file"));
        assertEquals(null, harness.trigger.get());
    }

    private static final class Harness {
        final WorkflowWebhookTriggerRepository triggerRepository =
                mock(WorkflowWebhookTriggerRepository.class);
        final WorkflowWebhookDeliveryRepository deliveryRepository =
                mock(WorkflowWebhookDeliveryRepository.class);
        final WorkflowService workflows = mock(WorkflowService.class);
        final WorkflowExecutionService executions = mock(WorkflowExecutionService.class);
        final BackgroundTaskRegistry tasks = mock(BackgroundTaskRegistry.class);
        final BackgroundTaskRegistry.Task task = mock(BackgroundTaskRegistry.Task.class);
        final SecurityContext security = mock(SecurityContext.class);
        final AtomicBoolean unsandboxed = new AtomicBoolean();
        final AtomicReference<WorkflowWebhookTriggerEntity> trigger = new AtomicReference<>();
        final Map<String, WorkflowWebhookDeliveryEntity> deliveries = new ConcurrentHashMap<>();
        final WorkflowWebhookTriggerService service;

        Harness() {
            when(security.currentUserId()).thenReturn(1L);
            when(workflows.compile(eq("wf-1"), anyMap(), eq(true)))
                    .thenReturn(mock(AgentPlan.class));
            when(workflows.get("wf-1")).thenReturn(new WorkflowDefinition(
                    "wf-1", "Flow", "", Map.of("type", "object", "properties", Map.of()),
                    null, Map.of(), Map.of(), true, 1, null, null));
            when(triggerRepository.save(any(WorkflowWebhookTriggerEntity.class)))
                    .thenAnswer(invocation -> {
                        WorkflowWebhookTriggerEntity entity = invocation.getArgument(0);
                        trigger.set(entity);
                        return entity;
                    });
            when(triggerRepository.findById(anyString()))
                    .thenAnswer(invocation -> Optional.ofNullable(trigger.get())
                            .filter(entity -> entity.getId().equals(invocation.getArgument(0))));
            when(triggerRepository.findByUserIdAndStatusOrderByCreatedAtAsc(1L, "ACTIVE"))
                    .thenAnswer(ignored -> Optional.ofNullable(trigger.get())
                            .filter(entity -> "ACTIVE".equals(entity.getStatus()))
                            .map(List::of).orElseGet(List::of));
            when(triggerRepository.findByIdAndUserIdAndStatus(anyString(), eq(1L), eq("ACTIVE")))
                    .thenAnswer(invocation -> Optional.ofNullable(trigger.get())
                            .filter(entity -> entity.getId().equals(invocation.getArgument(0)))
                            .filter(entity -> entity.getUserId() == 1L)
                            .filter(entity -> "ACTIVE".equals(entity.getStatus())));

            when(deliveryRepository.saveAndFlush(any(WorkflowWebhookDeliveryEntity.class)))
                    .thenAnswer(invocation -> {
                        WorkflowWebhookDeliveryEntity entity = invocation.getArgument(0);
                        if (deliveries.putIfAbsent(entity.getId(), entity) != null) {
                            throw new DataIntegrityViolationException("duplicate");
                        }
                        return entity;
                    });
            when(deliveryRepository.save(any(WorkflowWebhookDeliveryEntity.class)))
                    .thenAnswer(invocation -> {
                        WorkflowWebhookDeliveryEntity entity = invocation.getArgument(0);
                        deliveries.put(entity.getId(), entity);
                        return entity;
                    });
            when(deliveryRepository.findById(anyString()))
                    .thenAnswer(invocation -> Optional.ofNullable(
                            deliveries.get(invocation.getArgument(0))));
            doAnswer(invocation -> {
                deliveries.remove(invocation.getArgument(0));
                return null;
            }).when(deliveryRepository).deleteById(anyString());
            when(deliveryRepository.markSubmittedIfQueued(anyString(), anyString()))
                    .thenAnswer(invocation -> {
                        WorkflowWebhookDeliveryEntity entity = deliveries.get(
                                invocation.getArgument(0));
                        if (entity == null || !"QUEUED".equals(entity.getStatus())) return 0;
                        entity.setStatus("SUBMITTED");
                        entity.setTaskId(invocation.getArgument(1));
                        return 1;
                    });
            when(deliveryRepository.finishIfActive(anyString(), anyString(), any(), any()))
                    .thenAnswer(invocation -> {
                        WorkflowWebhookDeliveryEntity entity = deliveries.get(
                                invocation.getArgument(0));
                        if (entity == null || List.of("COMPLETED", "FAILED", "CANCELLED",
                                "INTERRUPTED").contains(entity.getStatus())) return 0;
                        entity.setStatus(invocation.getArgument(1));
                        entity.setCompletedAt(invocation.getArgument(2));
                        entity.setError(invocation.getArgument(3));
                        return 1;
                    });
            when(deliveryRepository.countByTriggerId(anyString()))
                    .thenAnswer(invocation -> deliveries.values().stream()
                            .filter(entity -> entity.getTriggerId().equals(invocation.getArgument(0)))
                            .count());
            when(deliveryRepository.findByStatusInOrderByAcceptedAtAsc(
                    List.of("CLAIMED", "QUEUED", "SUBMITTED")))
                    .thenReturn(new ArrayList<>());
            when(deliveryRepository.findByTriggerIdOrderByAcceptedAtDesc(
                    anyString(), any(org.springframework.data.domain.Pageable.class)))
                    .thenAnswer(invocation -> {
                        String triggerId = invocation.getArgument(0);
                        org.springframework.data.domain.Pageable page = invocation.getArgument(1);
                        return deliveries.values().stream()
                                .filter(entity -> triggerId.equals(entity.getTriggerId()))
                                .sorted((left, right) -> right.getAcceptedAt()
                                        .compareTo(left.getAcceptedAt()))
                                .limit(page.getPageSize())
                                .toList();
                    });

            when(task.id()).thenReturn("task-1");
            when(tasks.submit(anyLong(), any(BackgroundTaskRegistry.Priority.class),
                    anyString(), anyString(),
                    any(BackgroundTaskRegistry.TaskBody.class))).thenReturn(task);
            service = new WorkflowWebhookTriggerService(triggerRepository, deliveryRepository,
                    workflows, executions, tasks, security, unsandboxed::get);
        }
    }

    private static BackgroundTaskRegistry.TaskBody submittedBody(Harness harness) {
        ArgumentCaptor<BackgroundTaskRegistry.TaskBody> body =
                ArgumentCaptor.forClass(BackgroundTaskRegistry.TaskBody.class);
        verify(harness.tasks).submit(eq(1L),
                eq(BackgroundTaskRegistry.Priority.INTERACTIVE), eq("workflow-webhook"),
                anyString(), body.capture());
        return body.getValue();
    }
}
