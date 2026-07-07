package fan.summer.zhiflow.ai.adapter;

import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.ai.AiToolParam;
import fan.summer.zhiflow.api.ai.AiToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiToolCallbackTest {

    private static AiTool echoTool() {
        return new AiTool() {
            @Override public String getName()        { return "echo"; }
            @Override public String getDescription() { return "Echoes text"; }
            @Override public List<AiToolParam> getParameters() {
                return List.of(AiToolParam.of("text", "string", "Text to echo", true));
            }
            @Override public AiToolResult execute(Map<String, Object> args) {
                return AiToolResult.success("echo:" + args.get("text"));
            }
        };
    }

    @Test
    void toolDefinitionHasNameDescriptionSchema() {
        ToolCallback cb = new AiToolCallback(echoTool());
        ToolDefinition def = cb.getToolDefinition();
        assertEquals("echo", def.name());
        assertEquals("Echoes text", def.description());
        assertTrue(def.inputSchema().contains("\"text\""));
        assertTrue(def.inputSchema().contains("\"type\":\"string\""));
    }

    @Test
    void callParsesJsonArgsAndDelegates() {
        ToolCallback cb = new AiToolCallback(echoTool());
        String result = cb.call("{\"text\":\"hi\"}");
        assertEquals("echo:hi", result);
    }

    @Test
    void callReturnsErrorMessageOnException() {
        AiTool broken = new AiTool() {
            @Override public String getName()        { return "broken"; }
            @Override public String getDescription() { return "Always throws"; }
            @Override public List<AiToolParam> getParameters() { return List.of(); }
            @Override public AiToolResult execute(Map<String, Object> args) {
                throw new RuntimeException("boom");
            }
        };
        ToolCallback cb = new AiToolCallback(broken);
        String result = cb.call("{}");
        // AiToolCallback wraps exceptions into an error JSON, never throws
        assertNotNull(result);
        assertTrue(result.contains("boom") || result.contains("error"), "result=" + result);
    }
}
