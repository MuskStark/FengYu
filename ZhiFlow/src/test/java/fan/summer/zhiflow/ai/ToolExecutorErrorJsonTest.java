package fan.summer.zhiflow.ai;

import fan.summer.zhiflow.api.ai.AiServiceProvider;
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.ai.AiToolParam;
import fan.summer.zhiflow.api.ai.AiToolResult;
import fan.summer.zhiflow.ai.util.JsonHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutorErrorJsonTest {

    @BeforeEach
    void clear() {
        AiServiceProvider.clearTools();
    }

    @AfterEach
    void cleanup() {
        AiServiceProvider.clearTools();
    }

    @Test
    void executeUnknownToolReturnsJsonError() {
        AiToolResult r = ToolExecutor.execute("nonexistent_tool", Map.of());
        assertFalse(r.success());
        Map<String, Object> parsed = JsonHelper.parseObject(r.output());
        assertEquals(false, parsed.get("success"));
        assertNotNull(parsed.get("error"));
    }

    @Test
    void executeToolThatThrowsReturnsJsonError() {
        AiTool throwing = new AiTool() {
            public String getName() { return "thrower"; }
            public String getDescription() { return ""; }
            public List<AiToolParam> getParameters() { return List.of(); }
            public AiToolResult execute(Map<String, Object> args) {
                throw new RuntimeException("boom");
            }
        };
        AiServiceProvider.registerTool(throwing);

        AiToolResult r = ToolExecutor.execute("thrower", Map.of());
        assertFalse(r.success());
        Map<String, Object> parsed = JsonHelper.parseObject(r.output());
        assertEquals(false, parsed.get("success"));
        String err = (String) parsed.get("error");
        assertNotNull(err);
        assertTrue(err.contains("boom"), "error should contain the original message");
    }
}
