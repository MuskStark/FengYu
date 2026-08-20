package fan.summer.fengyu.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {
    @Test
    void mapsPluginRuntimeFailuresToSafeJson() {
        var response = new GlobalExceptionHandler().handlePluginFailure(
            new IllegalStateException("Plugin RPC failed: bad workbook"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(false, response.getBody().get("success"));
        assertEquals("Plugin RPC failed: bad workbook", response.getBody().get("error"));
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
}
