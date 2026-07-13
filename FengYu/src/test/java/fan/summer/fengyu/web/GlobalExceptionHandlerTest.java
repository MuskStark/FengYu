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
}
