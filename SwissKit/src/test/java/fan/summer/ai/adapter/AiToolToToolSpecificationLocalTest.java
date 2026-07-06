package fan.summer.ai.adapter;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import fan.summer.zhiflow.api.ai.AiServiceProvider;
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.ai.AiToolParam;
import fan.summer.zhiflow.api.ai.AiToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiToolToToolSpecificationLocalTest {

    @AfterEach
    void reset() {
        AiServiceProvider.setCurrentMode("local");
    }

    private static AiTool dualTool() {
        return new AiTool() {
            public String getName() { return "t"; }
            public String getDescription() { return "CLOUD-MARKER"; }
            public String getLocalDescription() { return "LOCAL-MARKER"; }
            public List<AiToolParam> getParameters() {
                return List.of(AiToolParam.of("cloudOnly", "string", "c"));
            }
            public List<AiToolParam> getLocalParameters() {
                return List.of(AiToolParam.of("localOnly", "string", "l"));
            }
            public AiToolResult execute(Map<String, Object> args) { return AiToolResult.success("ok"); }
        };
    }

    @Test
    void localModeProducesLocalDescriptionAndParams() {
        AiServiceProvider.setCurrentMode("local");
        ToolSpecification spec = AiToolToToolSpecification.convert(dualTool());
        assertEquals("LOCAL-MARKER", spec.description());
        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertNotNull(schema.properties().get("localOnly"));
        assertNull(schema.properties().get("cloudOnly"));
    }

    @Test
    void cloudModeProducesCloudDescriptionAndParams() {
        AiServiceProvider.setCurrentMode("openai");
        ToolSpecification spec = AiToolToToolSpecification.convert(dualTool());
        assertEquals("CLOUD-MARKER", spec.description());
        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertNotNull(schema.properties().get("cloudOnly"));
        assertNull(schema.properties().get("localOnly"));
    }
}
