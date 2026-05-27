package fan.summer.ai.tools;

import fan.summer.api.ai.AiTool;
import fan.summer.api.ai.AiToolParam;

import java.util.*;

public class ToolSchemaBuilder {

    private ToolSchemaBuilder() {}

    public static List<Map<String, Object>> buildOpenAiTools(List<AiTool> tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AiTool tool : tools) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", tool.getName());
            fn.put("description", tool.getDescription());
            fn.put("parameters", buildJsonSchema(tool.getParameters()));
            result.add(Map.of("type", "function", "function", fn));
        }
        return result;
    }

    public static List<Map<String, Object>> buildAnthropicTools(List<AiTool> tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AiTool tool : tools) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", tool.getName());
            t.put("description", tool.getDescription());
            t.put("input_schema", buildJsonSchema(tool.getParameters()));
            result.add(t);
        }
        return result;
    }

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
            sb.append(tool.getDescription()).append("\n");
            List<AiToolParam> params = tool.getParameters();
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
            prop.put("type", p.type());
            prop.put("description", p.description());
            properties.put(p.name(), prop);
            if (p.required()) required.add(p.name());
        }
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }
}
