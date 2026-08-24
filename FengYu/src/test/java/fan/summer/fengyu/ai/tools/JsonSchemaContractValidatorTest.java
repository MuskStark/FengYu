package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSchemaContractValidatorTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String CONTRACT = """
            {"type":"object","additionalProperties":false,
             "required":["success","items"],
             "properties":{
               "success":{"type":"boolean"},
               "items":{"type":"array","minItems":1,"items":{"type":"object",
                 "required":["name"],"properties":{"name":{"type":"string"}}}}
             }}
            """;

    @Test
    void validatesNestedGeneratedContractSubset() throws Exception {
        var schema = JSON.readTree(CONTRACT);
        assertDoesNotThrow(() -> JsonSchemaContractValidator.validate(
                Map.of("success", true, "items", List.of(Map.of("name", "East"))),
                schema, "Plugin output"));
        assertThrows(IllegalArgumentException.class, () -> JsonSchemaContractValidator.validate(
                Map.of("success", "true", "items", List.of()), schema, "Plugin output"));
        assertThrows(IllegalArgumentException.class, () -> JsonSchemaContractValidator.validate(
                Map.of("success", true, "items", List.of(Map.of("name", "East")), "extra", 1),
                schema, "Plugin output"));
    }

    @Test
    void validatesJsonTextAndDeclaredReferencePaths() throws Exception {
        var schema = JSON.readTree(CONTRACT);
        assertDoesNotThrow(() -> JsonSchemaContractValidator.validateJson(
                "{\"success\":true,\"items\":[{\"name\":\"East\"}]}", schema, "Pinned result"));
        assertThrows(IllegalArgumentException.class, () -> JsonSchemaContractValidator.validateJson(
                "not-json", schema, "Pinned result"));
        assertTrue(JsonSchemaContractValidator.declaresPath(schema, ".items[0].name"));
        assertFalse(JsonSchemaContractValidator.declaresPath(schema, ".items[0].missing"));
    }

    @Test
    void hostInputAcceptsAuthenticatedFileRefBridgeForWorkerStringPath() throws Exception {
        var schema = JSON.readTree("""
                {"type":"object","required":["filePath"],"properties":{
                  "filePath":{"type":"string","description":"Resolved FengYu FileRef path"}}}
                """);
        Map<String, Object> ref = Map.of("id", "grant-1", "name", "book.xlsx",
                "kind", "file", "access", "read", "size", 42);
        assertDoesNotThrow(() -> JsonSchemaContractValidator.validateHostInput(
                Map.of("filePath", ref), schema, "Plugin input"));
        assertThrows(IllegalArgumentException.class, () -> JsonSchemaContractValidator.validate(
                Map.of("filePath", ref), schema, "Ordinary input"));
        assertThrows(IllegalArgumentException.class, () -> JsonSchemaContractValidator.validateHostInput(
                Map.of("filePath", Map.of("id", "forged")), schema, "Plugin input"));
    }
}
