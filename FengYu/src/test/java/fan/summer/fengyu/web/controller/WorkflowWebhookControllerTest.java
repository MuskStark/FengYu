package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.workflow.WorkflowWebhookTriggerService;
import fan.summer.fengyu.ai.workflow.WorkflowWebhookAuthenticationException;
import fan.summer.fengyu.ai.tools.AiPermissionMode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowWebhookControllerTest {

    @Test
    void listsRecentDeliveriesForAnOwnedTrigger() {
        WorkflowWebhookTriggerService service = mock(WorkflowWebhookTriggerService.class);
        when(service.listDeliveries("hook-1", 25)).thenReturn(List.of(
                Map.of("taskId", "task-1", "status", "COMPLETED")));
        WorkflowWebhookController controller = new WorkflowWebhookController(service);

        List<Map<String, Object>> result = controller.deliveries("hook-1", 25);

        assertEquals(1, result.size());
        assertEquals("COMPLETED", result.getFirst().get("status"));
        verify(service).listDeliveries("hook-1", 25);
    }

    @Test
    void parsesJsonObjectAndReturns202ForANewDelivery() {
        WorkflowWebhookTriggerService service = mock(WorkflowWebhookTriggerService.class);
        when(service.deliver(eq("hook-1"), eq("secret"), eq("evt-1"),
                eq(Map.of("orderId", 42))))
                .thenReturn(new WorkflowWebhookTriggerService.DeliveryResult(
                        "hook-1", "task-1", true, false, "QUEUED", null));
        WorkflowWebhookController controller = new WorkflowWebhookController(service);

        var response = controller.deliver("hook-1", "secret", "evt-1",
                "{\"orderId\":42}".getBytes(StandardCharsets.UTF_8));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("task-1", response.getBody().taskId());
    }

    @Test
    void duplicateDeliveryReturns200AndTheOriginalTask() {
        WorkflowWebhookTriggerService service = mock(WorkflowWebhookTriggerService.class);
        when(service.deliver("hook-1", "secret", "evt-1", Map.of()))
                .thenReturn(new WorkflowWebhookTriggerService.DeliveryResult(
                        "hook-1", "task-1", true, true, "QUEUED", null));
        WorkflowWebhookController controller = new WorkflowWebhookController(service);

        var response = controller.deliver("hook-1", "secret", "evt-1", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().duplicate());
    }

    @Test
    void authenticatesBeforeRejectingNonObjectAndOversizedPayloads() {
        WorkflowWebhookTriggerService service = mock(WorkflowWebhookTriggerService.class);
        WorkflowWebhookController controller = new WorkflowWebhookController(service);

        assertThrows(IllegalArgumentException.class,
                () -> controller.deliver("hook-1", "secret", null,
                        "[1,2]".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> controller.deliver("hook-1", "secret", null,
                        new byte[WorkflowWebhookController.MAX_PAYLOAD_BYTES + 1]));
        verify(service, times(2)).authenticateDelivery("hook-1", "secret");
    }

    @Test
    void invalidCredentialWinsOverMalformedPayload() {
        WorkflowWebhookTriggerService service = mock(WorkflowWebhookTriggerService.class);
        doThrow(new WorkflowWebhookAuthenticationException())
                .when(service).authenticateDelivery("hook-1", "wrong");
        WorkflowWebhookController controller = new WorkflowWebhookController(service);

        assertThrows(WorkflowWebhookAuthenticationException.class,
                () -> controller.deliver("hook-1", "wrong", null,
                        "[1,2]".getBytes(StandardCharsets.UTF_8)));
        verify(service, times(0)).deliver(eq("hook-1"), eq("wrong"), eq(null), anyMap());
    }

    @Test
    void createResponseIncludesSecretOnlyForThatCall() {
        WorkflowWebhookTriggerService service = mock(WorkflowWebhookTriggerService.class);
        when(service.create("wf-1", "Orders", Map.of("region", "east"),
                AiPermissionMode.APPROVE_FOR_ME))
                .thenReturn(new WorkflowWebhookTriggerService.CreatedTrigger(
                        Map.of("triggerId", "hook-1", "endpoint", "/api/workflow-hooks/hook-1"),
                        "one-time-secret"));
        WorkflowWebhookController controller = new WorkflowWebhookController(service);

        var response = controller.create(new WorkflowWebhookController.CreateRequest(
                "wf-1", "Orders", Map.of("region", "east"),
                AiPermissionMode.APPROVE_FOR_ME));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("one-time-secret", response.getBody().get("secret"));
        assertEquals(WorkflowWebhookController.SECRET_HEADER,
                response.getBody().get("secretHeader"));
        verify(service).create("wf-1", "Orders", Map.of("region", "east"),
                AiPermissionMode.APPROVE_FOR_ME);
    }
}
