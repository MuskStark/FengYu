package fan.summer.fengyu.ai.tools;

import fan.summer.fengyu.ai.session.ConversationCompactor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Decides whether a request uses dynamic tool loading (the pi {@code setActiveTools} pattern):
 * only a small always-attached core plus an on-demand activation set is sent per round, while
 * the rest of the catalog is advertised by name in the system prompt and fetched through the
 * {@code search_tools} loader.
 *
 * <p>Mode semantics (setting {@code ai.tool_loading_mode}):
 * <ul>
 *   <li>{@code auto} (default) — dynamic loading only when the visible tool count exceeds
 *       {@code ai.tool_loading_threshold}; small deployments keep byte-for-byte today's
 *       full-catalog behaviour;</li>
 *   <li>{@code always} — on regardless of count (escape hatch for evaluation);</li>
 *   <li>{@code off} — never (escape hatch for models that follow the loader poorly).</li>
 * </ul>
 */
public final class ToolLoadingPolicy {

    public static final String MODE_AUTO = "auto";
    public static final String MODE_ALWAYS = "always";
    public static final String MODE_OFF = "off";
    public static final int DEFAULT_THRESHOLD = 25;
    public static final int MIN_THRESHOLD = 5;
    public static final int MAX_THRESHOLD = 500;

    /**
     * A tool definition estimated at or below this stays always-attached in dynamic mode: cheap
     * host tools ({@code skill}, {@code json_format}, {@code memory_*}, {@code search_tools}
     * itself) keep zero-latency availability while large browser/MCP schemas defer. The estimate
     * uses the same formula as the compactor's prompt-overhead accounting.
     */
    static final int CORE_TOOL_MAX_TOKENS = 400;

    private ToolLoadingPolicy() {
    }

    public static boolean dynamicLoading(String mode, Integer threshold, int visibleToolCount) {
        return switch (normalizeMode(mode)) {
            case MODE_ALWAYS -> true;
            case MODE_OFF -> false;
            default -> visibleToolCount > clampThreshold(threshold);
        };
    }

    public static String normalizeMode(String mode) {
        String value = mode == null ? "" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case MODE_ALWAYS, "on", "true" -> MODE_ALWAYS;
            case MODE_OFF, "false" -> MODE_OFF;
            default -> MODE_AUTO;
        };
    }

    public static int clampThreshold(Integer threshold) {
        if (threshold == null) return DEFAULT_THRESHOLD;
        return Math.max(MIN_THRESHOLD, Math.min(MAX_THRESHOLD, threshold));
    }

    /** Small definitions stay attached in dynamic mode; everything else loads on demand. */
    public static boolean isCoreTool(ToolCallback callback) {
        return definitionTokens(callback.getToolDefinition()) <= CORE_TOOL_MAX_TOKENS;
    }

    public static java.util.Set<String> toolNames(java.util.Collection<ToolCallback> tools) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        if (tools != null) {
            for (ToolCallback tool : tools) {
                ToolDefinition definition = tool.getToolDefinition();
                if (definition != null && definition.name() != null) names.add(definition.name());
            }
        }
        return names;
    }

    /**
     * Core tools plus everything the conversation activated; ordering follows the source list.
     * Request-bound tools are always attached even when their schema is large: they are the
     * explicit context of this turn (for example edit_current_flow) and requiring search_tools to
     * rediscover them would defeat the binding contract.
     */
    public static java.util.List<ToolCallback> attachedTools(java.util.List<ToolCallback> all,
            ToolActivationState activation) {
        java.util.List<ToolCallback> attached = new java.util.ArrayList<>();
        java.util.Set<String> bound = toolNames(BoundToolsContext.current());
        for (ToolCallback tool : all) {
            ToolDefinition definition = tool.getToolDefinition();
            if (definition == null || definition.name() == null) continue;
            if (bound.contains(definition.name()) || isCoreTool(tool)
                    || (activation != null && activation.isActive(definition.name()))) {
                attached.add(tool);
            }
        }
        return java.util.List.copyOf(attached);
    }

    /** The complement advertised in the system-prompt catalog and searched by the loader. */
    public static java.util.List<ToolCallback> deferredTools(java.util.List<ToolCallback> all,
            ToolActivationState activation) {
        java.util.Set<String> attached = toolNames(attachedTools(all, activation));
        java.util.List<ToolCallback> deferred = new java.util.ArrayList<>();
        for (ToolCallback tool : all) {
            ToolDefinition definition = tool.getToolDefinition();
            if (definition == null || definition.name() == null) continue;
            if (!attached.contains(definition.name())) deferred.add(tool);
        }
        return java.util.List.copyOf(deferred);
    }

    static long definitionTokens(ToolDefinition definition) {
        if (definition == null) return 0;
        return 12L
                + ConversationCompactor.estimateTextTokens(definition.name())
                + ConversationCompactor.estimateTextTokens(definition.description())
                + ConversationCompactor.estimateTextTokens(definition.inputSchema());
    }
}
