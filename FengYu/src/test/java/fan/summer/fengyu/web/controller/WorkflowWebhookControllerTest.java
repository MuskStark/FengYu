package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.workflow.WorkflowWebhookTriggerService;
import fan.summer.fengyu.ai.workflow.WorkflowWebhookAuthenticationException;
import fan.summer.fengyu.ai.tools.AiPermissionMode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

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

    private static MockHttpServletRequest requestWithBody(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/workflow-hooks/hook-1");
        request.setContent(body);
        return request;
    }

    /** A request whose body access fails loudly — proves the code path under test never reads it. */
    private static MockHttpServletRequest requestThatMustNotBeRead() {
        return new MockHttpServletRequest("POST", "/api/workflow-hooks/hook-1") {
            @Override
            public jakarta.servlet.ServletInputStream getInputStream() {
                throw new AssertionError("the request body must not be read on this path");
            }
        };
    }

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
    void parsesJsonObjectAndReturns202ForANewDelivery() throws Exception {
        WorkflowWebhookTriggerService service = mock(WorkflowWebhookTriggerService.class);
        when(service.deliver(eq("hook-1"), eq("secret"), eq("evt-1"),
                eq(Map.of("orderId", 42))))
                .thenReturn(new WorkflowWebhookTriggerService.DeliveryResult(
                        "hook-1", "task-1", true, false, "QUEUED", null));
        WorkflowWebhookController controller = new WorkflowWebhookController(service);

        var response = controller.deliver("hook-1", "secret", "evt-1",
                requestWithBody("{\"orderId\":42}".getBytes(StandardCharsets.UTF_8)));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("task-1", response.getBody().taskId());
    }

    @Test
    void duplicateDeliveryReturns200AndTheOriginalTask() throws Exception {
        WorkflowWebhookTriggerService service = mock(WorkflowWebhookTriggerService.class);
        when(service.deliver("hook-1", "secret", "evt-1", Map.of()))
                .thenReturn(new WorkflowWebhookTriggerService.DeliveryResult(
                        "hook-1", "task-1", true, true, "QUEUED", null));
        WorkflowWebhookController controller = new WorkflowWebhookController(service);

        var response = controller.deliver("hook-1", "secret", "evt-1",
                requestWithBody(new byte[0]));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().duplicate());
    }

    @Test
    void authenticatesBeforeRejectingNonObjectAndOversizedPayloads() {
        WorkflowWebhookTriggerService service = mock(WorkflowWebhookTriggerService.class);
        WorkflowWebhookController controller = new WorkflowWebhookController(service);

        assertThrows(IllegalArgumentException.class,
                () -> controller.deliver("hook-1", "secret", null,
                        requestWithBody("[1,2]".getBytes(StandardCharsets.UTF_8))));
        assertThrows(IllegalArgumentException.class,
                () -> controller.deliver("hook-1", "secret", null,
                        requestWithBody(new byte[WorkflowWebhookController.MAX_PAYLOAD_BYTES + 1])));
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
                        requestWithBody("[1,2]".getBytes(StandardCharsets.UTF_8))));
        verify(service, times(0)).deliver(eq("hook-1"), eq("wrong"), eq(null), anyMap());
    }

    /**
     * P2 regression: the secret is verified BEFORE any body byte is read — the request body
     * must never be materialized (heap spend) for an unauthenticated caller. The stub request
     * fails the test the moment its input stream is touched.
     */
    @Test
    void invalidCredentialNeverReadsTheRequestBody() {
        WorkflowWebhookTriggerService service = mock(WorkflowWebhookTriggerService.class);
        doThrow(new WorkflowWebhookAuthenticationException())
                .when(service).authenticateDelivery("hook-1", "wrong");
        WorkflowWebhookController controller = new WorkflowWebhookController(service);

        assertThrows(WorkflowWebhookAuthenticationException.class,
                () -> controller.deliver("hook-1", "wrong", null, requestThatMustNotBeRead()));
        verify(service, times(0)).deliver(eq("hook-1"), eq("wrong"), eq(null), anyMap());
    }

    /** P2 regression: an oversized DECLARED Content-Length is refused before any read. */
    @Test
    void oversizedDeclaredContentLengthIsRejectedBeforeReading() {
        WorkflowWebhookTriggerService service = mock(WorkflowWebhookTriggerService.class);
        WorkflowWebhookController controller = new WorkflowWebhookController(service);
        MockHttpServletRequest request = requestThatMustNotBeRead();
        request.addHeader("Content-Length",
                String.valueOf(WorkflowWebhookController.MAX_PAYLOAD_BYTES + 1));

        assertThrows(IllegalArgumentException.class,
                () -> controller.deliver("hook-1", "secret", null, request));
        verify(service, times(0)).deliver(eq("hook-1"), eq("secret"), eq(null), anyMap());
    }

    /**
     * P2 regression: with NO Content-Length (chunked-style), the actual streamed bytes are
     * still capped — a lying or absent header cannot bypass the limit.
     */
    @Test
    void actualBytesAreCappedWhenContentLengthIsAbsentOrLies() {
        WorkflowWebhookTriggerService service = mock(WorkflowWebhookTriggerService.class);
        WorkflowWebhookController controller = new WorkflowWebhookController(service);
        // No Content-Length header on purpose (MockHttpServletRequest does not add one) …
        MockHttpServletRequest absent = requestWithBody(
                new byte[WorkflowWebhookController.MAX_PAYLOAD_BYTES + 1]);
        assertThrows(IllegalArgumentException.class,
                () -> controller.deliver("hook-1", "secret", null, absent));
        // … and a LYING small header over a body that is actually over the cap.
        MockHttpServletRequest lying = requestWithBody(
                new byte[WorkflowWebhookController.MAX_PAYLOAD_BYTES + 1]);
        lying.addHeader("Content-Length", "10");
        assertThrows(IllegalArgumentException.class,
                () -> controller.deliver("hook-1", "secret", null, lying));
        verify(service, times(2)).authenticateDelivery("hook-1", "secret");
        verify(service, times(0)).deliver(eq("hook-1"), eq("secret"), eq(null), anyMap());
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
