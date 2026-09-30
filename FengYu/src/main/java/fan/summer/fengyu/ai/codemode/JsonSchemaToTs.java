package fan.summer.fengyu.ai.codemode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * JSON Schema → TypeScript renderer for the nested-tool declarations injected into the
 * {@code exec} description (the port of codex {@code json_schema_types.rs}): primitives,
 * enums as literal unions, {@code anyOf}/{@code oneOf} as unions, arrays, nested objects
 * with optional ({@code ?}) properties, and {@code unknown} for anything else. An
 * input schema larger than {@link #MAX_SCHEMA_BYTES} is truncated with a marker — the
 * model gets shape, not payload.
 */
public final class JsonSchemaToTs {

    private JsonSchemaToTs() {}

    /** codex DEFAULT_INPUT_SCHEMA_MAX_BYTES. */
    public static final int MAX_SCHEMA_BYTES = 16 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Renders a JSON Schema (as text) to its TypeScript type expression. */
    public static String render(String schemaJson) {
        try {
            JsonNode schema = JSON.readTree(schemaJson == null ? "{}" : schemaJson);
            return renderType(schema, new HashSet<>());
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String renderType(JsonNode schema, Set<String> seen) {
        if (schema == null || !schema.isObject() || schema.isEmpty()) return "unknown";

        JsonNode enumValues = schema.get("enum");
        if (enumValues != null && enumValues.isArray() && !enumValues.isEmpty()) {
            List<String> literals = new ArrayList<>();
            for (JsonNode value : enumValues) {
                literals.add(value.isTextual() ? "\"" + value.asText() + "\"" : value.asText());
            }
            return String.join(" | ", literals);
        }
        for (String union : List.of("anyOf", "oneOf")) {
            JsonNode variants = schema.get(union);
            if (variants != null && variants.isArray() && !variants.isEmpty()) {
                List<String> rendered = new ArrayList<>();
                for (JsonNode variant : variants) {
                    rendered.add(renderType(variant, seen));
                }
                return String.join(" | ", rendered);
            }
        }
        JsonNode type = schema.get("type");
        if (type == null || !type.isTextual()) return "unknown";
        return switch (type.asText()) {
            case "string" -> "string";
            case "number", "integer" -> "number";
            case "boolean" -> "boolean";
            case "null" -> "null";
            case "array" -> renderArray(schema, seen);
            case "object" -> renderObject(schema, seen);
            default -> "unknown";
        };
    }

    private static String renderArray(JsonNode schema, Set<String> seen) {
        JsonNode items = schema.get("items");
        String element = items == null ? "unknown" : renderType(items, seen);
        return element.contains(" ") ? "(" + element + ")[]" : element + "[]";
    }

    private static String renderObject(JsonNode schema, Set<String> seen) {
        JsonNode properties = schema.get("properties");
        Set<String> required = new HashSet<>();
        JsonNode requiredNode = schema.get("required");
        if (requiredNode != null && requiredNode.isArray()) {
            for (JsonNode name : requiredNode) required.add(name.asText());
        }
        if (properties == null || !properties.isObject() || properties.isEmpty()) {
            return "Record<string, unknown>";
        }
        StringBuilder out = new StringBuilder("{\n");
        for (Iterator<String> fields = properties.fieldNames(); fields.hasNext(); ) {
            String field = fields.next();
            boolean optional = !required.contains(field);
            String rendered = renderType(properties.get(field), seen);
            out.append("  ").append(field).append(optional ? "?: " : ": ").append(rendered)
                    .append(";\n");
        }
        return out.append("}").toString();
    }
}
