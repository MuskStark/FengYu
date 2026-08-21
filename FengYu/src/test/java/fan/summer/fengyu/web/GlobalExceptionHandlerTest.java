package fan.summer.fengyu.web;

import fan.summer.fengyu.ai.tasks.BackgroundTaskCapacityException;
import fan.summer.fengyu.ai.workflow.WorkflowRevisionConflictException;
import fan.summer.fengyu.ai.workflow.WorkflowWebhookAuthenticationException;
import fan.summer.fengyu.ai.workflow.WorkflowWebhookUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {
    @Test
    void mapsBackgroundQueueExhaustionToRetryable429() {
        var response = new GlobalExceptionHandler().handleBackgroundTaskCapacity(
                new BackgroundTaskCapacityException(16, 128, 1));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("1", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertEquals(true, response.getBody().get("retryable"));
        assertEquals("global", response.getBody().get("capacityScope"));
        assertEquals(1, response.getBody().get("retryAfterSeconds"));
    }

    @Test
    void identifiesOwnerQueueLoadShedding() {
        var response = new GlobalExceptionHandler().handleBackgroundTaskCapacity(
                new BackgroundTaskCapacityException(32, 1));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("owner", response.getBody().get("capacityScope"));
    }

    @Test
    void identifiesPriorityAdmissionLoadShedding() {
        var response = new GlobalExceptionHandler().handleBackgroundTaskCapacity(
                new BackgroundTaskCapacityException("batch", 16, 1));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("owner-priority", response.getBody().get("capacityScope"));
        assertEquals("batch", response.getBody().get("capacityPriority"));
    }

    @Test
    void identifiesGlobalPriorityAdmissionLoadShedding() {
        var response = new GlobalExceptionHandler().handleBackgroundTaskCapacity(
                BackgroundTaskCapacityException.globalPriority("normal", 96, 1));

        assertEquals("global-priority", response.getBody().get("capacityScope"));
        assertEquals("normal", response.getBody().get("capacityPriority"));
    }

    @Test
    void mapsWorkflowRevisionConflictTo409WithBothRevisions() {
        var response = new GlobalExceptionHandler().handleWorkflowRevisionConflict(
                new WorkflowRevisionConflictException("flow-1", 3, 5));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(false, response.getBody().get("success"));
        assertEquals("flow-1", response.getBody().get("workflowId"));
        assertEquals(3, response.getBody().get("expectedRevision"));
        assertEquals(5, response.getBody().get("actualRevision"));
    }

    @Test
    void mapsPluginRuntimeFailuresToSafeJson() {
        var response = new GlobalExceptionHandler().handlePluginFailure(
            new IllegalStateException("Plugin RPC failed: bad workbook"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(false, response.getBody().get("success"));
        assertEquals("Plugin RPC failed: bad workbook", response.getBody().get("error"));
    }

    @Test
    void mapsWebhookAuthenticationAndSafetyPausePrecisely() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        assertEquals(HttpStatus.UNAUTHORIZED,
                handler.handleWebhookAuthentication(
                        new WorkflowWebhookAuthenticationException()).getStatusCode());
        assertEquals(HttpStatus.CONFLICT,
                handler.handleWebhookUnavailable(
                        new WorkflowWebhookUnavailableException("sandbox weakened")).getStatusCode());
    }

    @Test
    void fallsBackWhenPluginRuntimeFailureHasNoMessage() {
        var handler = new GlobalExceptionHandler();

        for (String message : new String[]{null, "", "  "}) {
            var response = handler.handlePluginFailure(new IllegalStateException(message));
            assertEquals("Plugin runtime failed", response.getBody().get("error"));
        }
    }

    @Test
    void mapsIoFailureTo500WithTheRealReason() {
        var response = new GlobalExceptionHandler().handleIoFailure(
            new java.io.IOException("Directory not empty: .fengyu/plugins/com.example"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(false, response.getBody().get("success"));
        assertEquals("I/O failure: Directory not empty: .fengyu/plugins/com.example",
            response.getBody().get("error"));
    }

    @Test
    void mapsIoFailureWithoutMessageToGenericReason() {
        var response = new GlobalExceptionHandler().handleIoFailure(new java.io.IOException());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("I/O failure while processing the request", response.getBody().get("error"));
    }

    @Test
    void mapsUnexpectedRuntimeExceptionWithClassAndMessage() {
        var response = new GlobalExceptionHandler().handleUnexpected(
            new NullPointerException("version is null"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(false, response.getBody().get("success"));
        assertEquals("NullPointerException: version is null", response.getBody().get("error"));
    }

    @Test
    void mapsUnexpectedRuntimeExceptionWithoutMessageToClassName() {
        var response = new GlobalExceptionHandler().handleUnexpected(
            new NullPointerException());
        assertEquals("Internal error: NullPointerException", response.getBody().get("error"));
    }

    @Test
    void preservesResponseStatusExceptionStatusInsteadOfDegradingTo500() {
        var response = new GlobalExceptionHandler().handleUnexpected(
            new org.springframework.web.server.ResponseStatusException(
                HttpStatus.CONFLICT, "Builtin skills cannot be disabled"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(false, response.getBody().get("success"));
        assertEquals("Builtin skills cannot be disabled", response.getBody().get("error"));
    }

    @Test
    void preservesFrameworkErrorResponseStatusLikeAsyncRequestTimeout() {
        var response = new GlobalExceptionHandler().handleUnexpected(
            new org.springframework.web.context.request.async.AsyncRequestTimeoutException());

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(false, response.getBody().get("success"));
    }

    @Test
    void responseStatusExceptionWithoutReasonStillCarriesItsStatus() {
        var response = new GlobalExceptionHandler().handleUnexpected(
            new org.springframework.web.server.ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("Request failed with HTTP 429", response.getBody().get("error"));
    }
}
