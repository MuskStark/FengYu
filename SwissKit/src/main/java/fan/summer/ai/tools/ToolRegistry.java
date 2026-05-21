package fan.summer.ai.tools;

import fan.summer.api.ai.AiTool;
import fan.summer.api.ai.AiToolParam;
import fan.summer.api.ai.AiToolResult;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of tools that can be invoked by the AI model during generation.
 * Thread-safe: tools can be registered/unregistered from any thread.
 */
public class ToolRegistry {

    private final Map<String, AiTool> tools = new ConcurrentHashMap<>();

    public void register(AiTool tool) {
        tools.put(tool.getName(), tool);
    }

    public void unregister(String name) {
        tools.remove(name);
    }

    public List<AiTool> getAll() {
        return List.copyOf(tools.values());
    }

    public boolean hasTools() {
        return !tools.isEmpty();
    }

    /**
     * Execute a registered tool by name.
     *
     * @return execution result, or an error result if the tool is not found
     */
    public AiToolResult execute(String toolName, Map<String, Object> arguments) {
        AiTool tool = tools.get(toolName);
        if (tool == null) {
            return AiToolResult.error("Tool not found: " + toolName);
        }
        try {
            return tool.execute(arguments);
        } catch (Exception e) {
            return AiToolResult.error("Tool execution error: " + e.getMessage());
        }
    }

    /**
     * Build a JSON-like tool definitions string suitable for inclusion in
     * the system prompt so the model knows which tools are available.
     */
    public String buildToolDefinitions() {
        if (tools.isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append("You have access to the following tools. ");
        sb.append("To call a tool, output a JSON block in this exact format:\n");
        sb.append("```json\n{\"name\": \"tool_name\", \"arguments\": {\"param\": \"value\"}}\n```\n\n");
        sb.append("Available tools:\n\n");

        for (AiTool tool : tools.values()) {
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

        sb.append("When you need to use a tool, output ONLY the JSON block. ");
        sb.append("After receiving the tool result, you can continue answering.\n");
        return sb.toString();
    }
}
