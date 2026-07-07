package fan.summer.zhiflow.ai.adapter;

import fan.summer.zhiflow.api.ai.AiToolParam;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolSchemaJsonTest {

    @Test
    void emptyParamsYieldsEmptyObjectSchema() {
        String s = ToolSchemaJson.build(List.of());
        assertEquals("{\"type\":\"object\",\"properties\":{},\"required\":[]}", s);
    }

    @Test
    void primitiveStringParam() {
        String s = ToolSchemaJson.build(List.of(
                AiToolParam.of("city", "string", "City name", true)));
        assertTrue(s.contains("\"city\""));
        assertTrue(s.contains("\"type\":\"string\""));
        assertTrue(s.contains("\"required\":[\"city\"]"));
    }

    @Test
    void enumParamEmitsEnumArray() {
        String s = ToolSchemaJson.build(List.of(
                AiToolParam.of("unit", "string", "Temperature unit", true,
                        List.of("celsius", "fahrenheit"))));
        assertTrue(s.contains("\"enum\":[\"celsius\",\"fahrenheit\"]"));
    }

    @Test
    void integerAndBooleanTypes() {
        String s = ToolSchemaJson.build(List.of(
                AiToolParam.of("count", "integer", "How many", false),
                AiToolParam.of("verbose", "boolean", "Verbose output", false)));
        assertTrue(s.contains("\"type\":\"integer\""));
        assertTrue(s.contains("\"type\":\"boolean\""));
        // nothing required
        assertTrue(s.contains("\"required\":[]"));
    }
}
