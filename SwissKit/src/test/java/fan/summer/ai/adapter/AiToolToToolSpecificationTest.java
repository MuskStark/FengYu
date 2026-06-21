package fan.summer.ai.adapter;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.*;
import fan.summer.api.ai.AiTool;
import fan.summer.api.ai.AiToolParam;
import fan.summer.api.ai.AiToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiToolToToolSpecificationTest {

    private static AiTool tool(String name, List<AiToolParam> params) {
        return new AiTool() {
            public String getName() { return name; }
            public String getDescription() { return "desc-" + name; }
            public List<AiToolParam> getParameters() { return params; }
            public AiToolResult execute(Map<String, Object> args) { return AiToolResult.success("ok"); }
        };
    }

    @Test
    void convertsBasicStringParam() {
        AiTool t = tool("t1", List.of(AiToolParam.of("path", "string", "File path")));
        ToolSpecification spec = AiToolToToolSpecification.convert(t);

        assertEquals("t1", spec.name());
        assertEquals("desc-t1", spec.description());
        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertNotNull(schema.properties().get("path"));
        assertTrue(schema.required().contains("path"));
    }

    @Test
    void convertsOptionalParam() {
        AiTool t = tool("t2", List.of(
            AiToolParam.of("path", "string", "File path"),
            AiToolParam.of("limit", "integer", "Max rows", false)
        ));
        ToolSpecification spec = AiToolToToolSpecification.convert(t);
        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();

        assertTrue(schema.required().contains("path"));
        assertFalse(schema.required().contains("limit"));
    }

    @Test
    void convertsEnumParam() {
        AiTool t = tool("t3", List.of(
            AiToolParam.of("mode", "string", "Split mode", true, List.of("SSM", "SCM", "SCPM"))
        ));
        ToolSpecification spec = AiToolToToolSpecification.convert(t);
        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();

        JsonEnumSchema mode = (JsonEnumSchema) schema.properties().get("mode");
        assertEquals(List.of("SSM", "SCM", "SCPM"), mode.enumValues());
    }

    @Test
    void emptyParamsProducesEmptyObjectSchema() {
        AiTool t = tool("t4", List.of());
        ToolSpecification spec = AiToolToToolSpecification.convert(t);
        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertNotNull(schema);
        assertTrue(schema.properties().isEmpty());
    }
}
