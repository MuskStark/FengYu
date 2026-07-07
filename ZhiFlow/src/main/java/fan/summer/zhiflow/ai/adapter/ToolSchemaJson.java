package fan.summer.zhiflow.ai.adapter;

import fan.summer.zhiflow.api.ai.AiToolParam;

import java.util.List;

/**
 * Builds a JSON-schema <strong>string</strong> describing an {@link AiToolParam} list,
 * for use as {@code DefaultToolDefinition.inputSchema()}.
 *
 * <p>Spring AI's {@code ToolDefinition.inputSchema()} returns a JSON-schema
 * <em>string</em> (not an object), so this builder hand-rolls the JSON. It is the
 * moral equivalent of the old LangChain4j {@code AiToolToToolSpecification}, but
 * emits schema JSON rather than LC4j {@code JsonSchemaElement} objects.
 *
 * <p>Supported types: {@code string} (incl. enums), {@code integer}, {@code number},
 * {@code boolean}. Array suffix {@code "type[]"} is mapped to {@code {"type":"array",...}}.
 * Unknown types default to {@code string}.
 */
public final class ToolSchemaJson {

    private ToolSchemaJson() {}

    public static String build(List<AiToolParam> params) {
        StringBuilder props = new StringBuilder("{");
        StringBuilder required = new StringBuilder("[");

        boolean firstProp = true;
        boolean firstReq = true;
        for (AiToolParam p : params) {
            if (!firstProp) props.append(",");
            firstProp = false;
            props.append('"').append(escape(p.name())).append("\":")
                 .append(schemaForParam(p));

            if (p.required()) {
                if (!firstReq) required.append(",");
                firstReq = false;
                required.append('"').append(escape(p.name())).append('"');
            }
        }
        props.append("}");
        required.append("]");

        return "{\"type\":\"object\",\"properties\":" + props
             + ",\"required\":" + required + "}";
    }

    private static String schemaForParam(AiToolParam p) {
        String desc = escape(p.description() == null ? "" : p.description());
        // Enum overrides any type — emit {"type":"string","enum":[...],"description":"..."}
        if (p.enumValues() != null && !p.enumValues().isEmpty()) {
            StringBuilder enumArr = new StringBuilder("[");
            boolean first = true;
            for (String e : p.enumValues()) {
                if (!first) enumArr.append(",");
                first = false;
                enumArr.append('"').append(escape(e)).append('"');
            }
            enumArr.append("]");
            return "{\"type\":\"string\",\"enum\":" + enumArr + ",\"description\":\"" + desc + "\"}";
        }
        String type = p.type() == null ? "string" : p.type();
        if (type.endsWith("[]")) {
            String elem = type.substring(0, type.length() - 2);
            return "{\"type\":\"array\",\"items\":{\"type\":\"" + mapType(elem)
                 + "\"},\"description\":\"" + desc + "\"}";
        }
        return "{\"type\":\"" + mapType(type) + "\",\"description\":\"" + desc + "\"}";
    }

    private static String mapType(String t) {
        return switch (t) {
            case "integer", "number", "boolean" -> t;
            default -> "string";
        };
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
