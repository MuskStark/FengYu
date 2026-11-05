package fan.summer.fengyu.ai.config;

import fan.summer.fengyu.ai.AiToolResult;

import java.util.Map;

/**
 * Static bridge between the AI tool loop / agent runner (which fire tool events and know the
 * wire tool name) and {@link AiToolRegistry} (which owns the installed manifests and their
 * {@code aiTools[].app} declarations). Registered once by {@code AiToolDiscoveryConfig} when
 * the singleton registry is created — the same holder idiom {@code McpUiRuntimeHolder} uses
 * for the MCP Apps catalog.
 *
 * <p>Projection rules mirror the MCP holder: the map is additive render metadata for the SSE
 * envelope only, the result the MODEL sees is never modified, and a projection failure must
 * never break the tool event.</p>
 */
public final class PluginUiRuntimeHolder {

    private PluginUiRuntimeHolder() {}

    /** The narrow surface the AI loop needs from the registry; implemented by the registry. */
    public interface Runtime {
        Map<String, Object> pluginUi(String toolName);
    }

    private static volatile Runtime runtime;

    public static void register(Runtime impl) {
        runtime = impl;
    }

    public static void clear() {
        runtime = null;
    }

    /**
     * Attaches the plugin app-card descriptor to a tool result when {@code toolName} belongs to
     * a plugin tool that declares an {@code app} view and no descriptor is attached yet (the
     * MCP projection runs first). Returns the input unchanged otherwise.
     */
    public static AiToolResult attach(String toolName, AiToolResult result) {
        Runtime current = runtime;
        if (current == null || toolName == null || result == null || result.ui() != null) {
            return result;
        }
        try {
            Map<String, Object> ui = current.pluginUi(toolName);
            if (ui == null || ui.isEmpty()) return result;
            return new AiToolResult(result.success(), result.output(), ui);
        } catch (RuntimeException degraded) {
            // A projection failure must never break the tool event.
            return result;
        }
    }

    /**
     * The bare descriptor for a tool name (agent-run {@code step_complete} events): null when
     * no installed plugin declares an app view for the name.
     */
    public static Map<String, Object> uiFor(String toolName) {
        Runtime current = runtime;
        if (current == null || toolName == null) return null;
        try {
            return current.pluginUi(toolName);
        } catch (RuntimeException degraded) {
            return null;
        }
    }
}
