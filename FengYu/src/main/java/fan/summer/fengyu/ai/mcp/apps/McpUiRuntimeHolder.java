package fan.summer.fengyu.ai.mcp.apps;

import fan.summer.fengyu.ai.AiToolResult;

/**
 * Static bridge between the AI tool loop (which fires SSE tool events and knows the wire tool
 * name + raw argument JSON) and {@link fan.summer.fengyu.ai.mcp.McpRuntimeManager} (which owns
 * the MCP Apps catalog and the captured raw results). Registered at manager start, cleared at
 * stop — the same holder idiom {@code ChatModelConfig} uses for its observation registry.
 */
public final class McpUiRuntimeHolder {

    private McpUiRuntimeHolder() {}

    /** The narrow surface the AI loop needs from the MCP runtime; implemented by the manager. */
    public interface Runtime {
        McpToolUiMeta.UiToolInfo uiTool(String wireName);
        McpToolUiMeta.CapturedResult takeCaptured(String wireName, String argsJson);
    }

    private static volatile Runtime runtime;

    public static void register(Runtime impl) {
        runtime = impl;
    }

    public static void clear() {
        runtime = null;
    }

    /**
     * Attaches the MCP Apps projection to a tool result when {@code toolName} is a UI-capable
     * tool and a captured raw result is waiting. Returns the input unchanged otherwise — the
     * result the MODEL sees is never modified, only the SSE envelope.
     */
    public static AiToolResult attachUi(String toolName, String argsJson, AiToolResult result) {
        Runtime current = runtime;
        if (current == null || toolName == null || result == null) return result;
        try {
            McpToolUiMeta.UiToolInfo info = current.uiTool(toolName);
            if (info == null) return result;
            McpToolUiMeta.CapturedResult captured = current.takeCaptured(toolName, argsJson);
            return new AiToolResult(result.success(), result.output(),
                    McpToolUiMeta.projectUiPayload(info, captured));
        } catch (RuntimeException degraded) {
            // A projection failure must never break the tool event.
            return result;
        }
    }
}
