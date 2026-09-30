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
    void surfacesSummaryWhenPluginsOmitTheErrorField() {
        // The dominant official-plugin convention: success:false + localized summary.
        var result = ToolResultStatus.toAiResult(
                "{\"success\":false,\"summary\":\"Call excel_analyze first.\"}");
        assertFalse(result.success());
        assertEquals("Call excel_analyze first.", result.output());
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                ToolResultStatus.requireSuccess("{\"success\":false,\"summary\":\"Call excel_analyze first.\"}"));
        assertEquals("Call excel_analyze first.", thrown.getMessage());
    }

    @Test
    void fallsBackToRawOutputWhenNeitherErrorNorSummaryCarriesAMessage() {
        var result = ToolResultStatus.toAiResult("{\"success\":false,\"summary\":\"\"}");
        assertFalse(result.success());
        assertEquals("{\"success\":false,\"summary\":\"\"}", result.output());
    }

    @Test
    void preservesOrdinarySuccessfulOutput() {
        assertTrue(ToolResultStatus.toAiResult("ok").success());
        assertEquals("ok", ToolResultStatus.requireSuccess("ok"));
    }
}
