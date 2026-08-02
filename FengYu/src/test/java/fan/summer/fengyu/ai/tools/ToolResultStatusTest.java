package fan.summer.fengyu.ai.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolResultStatusTest {
    @Test
    void recognizesStructuredToolFailure() {
        var result = ToolResultStatus.toAiResult("{\"success\":false,\"error\":\"denied\"}");
        assertFalse(result.success());
        assertEquals("denied", result.output());
        assertThrows(IllegalStateException.class, () ->
                ToolResultStatus.requireSuccess("{\"success\":false,\"error\":\"denied\"}"));
    }

    @Test
    void preservesOrdinarySuccessfulOutput() {
        assertTrue(ToolResultStatus.toAiResult("ok").success());
        assertEquals("ok", ToolResultStatus.requireSuccess("ok"));
    }
}
