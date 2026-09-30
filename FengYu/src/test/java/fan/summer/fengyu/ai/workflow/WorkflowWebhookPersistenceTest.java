package fan.summer.fengyu.ai.workflow;

import fan.summer.fengyu.FengYuApplication;
import fan.summer.fengyu.ai.agent.AgentPlan;
import fan.summer.fengyu.ai.tasks.BackgroundTaskRegistry;
import fan.summer.fengyu.database.repository.ai.WorkflowWebhookDeliveryRepository;
import fan.summer.fengyu.database.repository.ai.WorkflowWebhookTriggerRepository;
import fan.summer.fengyu.security.NoopSecurityContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real-schema proof for hashed secrets and atomic idempotency claims. */
@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = FengYuApplication.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WorkflowWebhookPersistenceTest {

    @Autowired WorkflowWebhookTriggerRepository triggers;
    @Autowired WorkflowWebhookDeliveryRepository deliveries;

    @Test
    void triggerAndHashedDeliverySurviveFreshServiceInstances() {
        WorkflowService workflows = mock(WorkflowService.class);
        when(workflows.get("wf-1")).thenReturn(new WorkflowDefinition(
                "wf-1", "Flow", "", Map.of("type", "object", "properties", Map.of()),
                null, Map.of(), Map.of(), true, 1, null, null));
        when(workflows.compile(eq("wf-1"), anyMap(), eq(true)))
                .thenReturn(mock(AgentPlan.class));
        BackgroundTaskRegistry tasks = mock(BackgroundTaskRegistry.class);
        BackgroundTaskRegistry.Task task = mock(BackgroundTaskRegistry.Task.class);
        when(task.id()).thenReturn("task-persisted");
        when(tasks.submit(anyLong(), any(BackgroundTaskRegistry.Priority.class),
                anyString(), anyString(),
                any(BackgroundTaskRegistry.TaskBody.class))).thenReturn(task);

        WorkflowWebhookTriggerService first = new WorkflowWebhookTriggerService(
                triggers, deliveries, workflows, mock(WorkflowExecutionService.class), tasks,
                new NoopSecurityContext(), () -> false);
        WorkflowWebhookTriggerService.CreatedTrigger created = first.create(
                "wf-1", "Orders", Map.of("region", "east"));
        String triggerId = (String) created.trigger().get("triggerId");
        String storedHash = triggers.findById(triggerId).orElseThrow().getSecretHash();
        assertNotEquals(created.secret(), storedHash);
        assertTrue(storedHash.matches("[0-9a-f]{64}"));

        WorkflowWebhookTriggerService.DeliveryResult accepted = first.deliver(
                triggerId, created.secret(), "order-42", Map.of("orderId", 42));
        WorkflowWebhookTriggerService second = new WorkflowWebhookTriggerService(
                triggers, deliveries, workflows, mock(WorkflowExecutionService.class), tasks,
                new NoopSecurityContext(), () -> false);
        WorkflowWebhookTriggerService.DeliveryResult duplicate = second.deliver(
                triggerId, created.secret(), "order-42", Map.of("orderId", 42));
        WorkflowWebhookTriggerService.DeliveryResult unkeyed = second.deliver(
                triggerId, created.secret(), null, Map.of("orderId", 43));

        assertEquals("task-persisted", accepted.taskId());
        assertTrue(duplicate.duplicate());
        assertEquals("task-persisted", duplicate.taskId());
        assertFalse(unkeyed.duplicate());
        assertEquals(2, deliveries.countByTriggerId(triggerId));
        assertEquals(2, triggers.findById(triggerId).orElseThrow().getFires());
        assertTrue(second.listDeliveries(triggerId, 20).stream()
                .anyMatch(row -> Boolean.FALSE.equals(row.get("idempotencyKeyPresent"))));

        var keyed = deliveries.findByTriggerIdOrderByAcceptedAtDesc(
                        triggerId, PageRequest.of(0, 20)).stream()
                .filter(row -> Boolean.TRUE.equals(row.getIdempotencyKeyPresent()))
                .findFirst().orElseThrow();
        assertEquals(1, deliveries.markSubmittedIfQueued(keyed.getId(), keyed.getTaskId()));
        assertEquals(1, deliveries.finishIfActive(keyed.getId(), "CANCELLED", Instant.now(),
                "cancelled first"));
        assertEquals(0, deliveries.finishIfActive(keyed.getId(), "COMPLETED", Instant.now(), null));
        var terminal = deliveries.findById(keyed.getId()).orElseThrow();
        assertEquals("CANCELLED", terminal.getStatus());
        assertEquals("cancelled first", terminal.getError());
    }
}
