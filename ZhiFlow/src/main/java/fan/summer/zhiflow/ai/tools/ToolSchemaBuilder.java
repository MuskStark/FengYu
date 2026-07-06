package fan.summer.zhiflow.ai.tools;

import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.ai.AiToolParam;

import java.util.*;

/**
 * Builds tool schema representations suitable for different AI provider APIs.
 *
 * <p>This class provides factory methods to produce tool definition maps for:</p>
 * <ul>
 *   <li><b>OpenAI</b> — {@link #buildOpenAiTools(List)} produces the
 *       {@code tools} array format using a JSON Schema for the
 *       {@code parameters} field.</li>
 *   <li><b>Anthropic</b> — {@link #buildAnthropicTools(List)} produces the
 *       Claude tool definition format with an {@code input_schema}.</li>
 * </ul>
 *
 * <p>{@link #buildPromptDefinitions(List)} generates a human-readable markdown
 * section describing all tools for insertion into a system prompt, including
 * parameter names, types, required/optional markers, and the calling convention.</p>
 *
 * @see AiTool
 * @see AiToolParam
 */
public class ToolSchemaBuilder {

    private ToolSchemaBuilder() {}

    /**
     * Builds an OpenAI-format tool definitions list from a collection of tools.
     *
     * <p>Each entry in the returned list has the shape:</p>
     * <pre>{@code {"type": "function", "function": {"name": "...", "description": "...", "parameters": {...}}}}
     *
     * @param tools the list of {@link AiTool} instances to convert
     * @return a new list of maps ready for JSON serialisation as the OpenAI {@code tools} array
     * @see #buildJsonSchema(List)
     */
    public static List<Map<String, Object>> buildOpenAiTools(List<AiTool> tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AiTool tool : tools) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", tool.getName());
            fn.put("description", AiToolDescriptions.pickDescription(tool));
            fn.put("parameters", buildJsonSchema(AiToolDescriptions.pickParameters(tool)));
            result.add(Map.of("type", "function", "function", fn));
        }
        return result;
    }

    /**
     * Builds an Anthropic-format tool definitions list from a collection of tools.
     *
     * <p>Each entry in the returned list has the shape:</p>
     * <pre>{@code {"name": "...", "description": "...", "input_schema": {...}}}
     *
     * @param tools the list of {@link AiTool} instances to convert
     * @return a new list of maps ready for JSON serialisation as the Anthropic {@code tools} array
     * @see #buildJsonSchema(List)
     */
    public static List<Map<String, Object>> buildAnthropicTools(List<AiTool> tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AiTool tool : tools) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", tool.getName());
            t.put("description", AiToolDescriptions.pickDescription(tool));
            t.put("input_schema", buildJsonSchema(AiToolDescriptions.pickParameters(tool)));
            result.add(t);
        }
        return result;
    }

    /**
     * Generates a markdown section that describes all provided tools in a format
     * suitable for inclusion in a system prompt.
     *
     * <p>The output includes the JSON calling convention, the "important" usage rule,
     * an example invocation, and a parameter table for each tool.</p>
     *
     * @param tools the list of tools to document; an empty list yields an empty string
     * @return a markdown string beginning with {@code # Tools\n\n}
     */
    public static String buildPromptDefinitions(List<AiTool> tools) {
        if (tools.isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append("# Tools\n\n");
        sb.append("You can call tools by outputting a JSON object with \"name\" and \"arguments\" fields.\n");
        sb.append("Format:\n```\n{\"name\": \"<tool_name>\", \"arguments\": {<param>: <value>}}\n```\n\n");
        sb.append("IMPORTANT: When a user's request requires using a tool, you MUST call the tool directly. ");
        sb.append("Do NOT describe how to use it or ask for confirmation. Just call it.\n\n");
        sb.append("Example — user says \"analyze this Excel file /path/to/file.xlsx\":\n");
        sb.append("{\"name\": \"excel_analyze\", \"arguments\": {\"filePath\": \"/path/to/file.xlsx\"}}\n\n");
        sb.append("Available tools:\n\n");

        for (AiTool tool : tools) {
            sb.append("### ").append(tool.getName()).append("\n");
            sb.append(AiToolDescriptions.pickDescription(tool)).append("\n");
            List<AiToolParam> params = AiToolDescriptions.pickParameters(tool);
            if (!params.isEmpty()) {
                sb.append("Parameters:\n");
                for (AiToolParam p : params) {
                    sb.append("- ").append(p.name()).append(" (").append(p.type()).append(")");
                    if (p.required()) sb.append(" [required]");
                    sb.append(": ").append(p.description()).append("\n");
                }
            }
            sb.append("\n");
        }

        sb.append("After receiving a tool result, you may call another tool or provide a final answer.\n");
        return sb.toString();
    }

    private static Map<String, Object> buildJsonSchema(List<AiToolParam> params) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (AiToolParam p : params) {
            Map<String, Object> prop = new LinkedHashMap<>();
            if (p.type().endsWith("[]")) {
                prop.put("type", "array");
                prop.put("items", Map.of("type", p.type().replace("[]", "")));
            } else {
                prop.put("type", p.type());
            }
            prop.put("description", p.description());
            if (!p.type().endsWith("[]") && !p.enumValues().isEmpty()) {
                prop.put("enum", p.enumValues());
            }
            properties.put(p.name(), prop);
            if (p.required()) required.add(p.name());
        }
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }
}
