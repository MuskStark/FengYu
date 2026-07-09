package fan.summer.zhiflow.api.plugin;

import java.util.Map;

/**
 * 4.0.0 plugin contract (UI plugin). Each plugin = backend logic ({@link #invoke})
 * plus a self-contained UI delivered as an ESM micro-frontend
 * ({@link PluginDescriptor#uiEntry()}), rendered by the host at a designated location.
 *
 * <p>AI capability is optional and independent: if a plugin supports AI calls, it
 * provides Spring AI-native {@code ToolCallback} beans (annotated with {@code @Tool}
 * or implementing {@code ToolCallback}) in the same module. {@link PluginDescriptor#supportsAi()}
 * is metadata only; the actual tools are Spring AI beans.
 */
public interface ZhiFlowPlugin {

    PluginDescriptor descriptor();

    /**
     * Backend JSON-RPC. The UI micro-frontend calls this via
     * {@code POST /api/plugins/{id}/invoke}.
     *
     * @param action plugin-defined action string (e.g. "render")
     * @param args   action arguments (JSON-deserialized map)
     * @return a JSON-serializable result (controller serializes it)
     * @throws IllegalArgumentException if the action is unknown or args are invalid
     */
    Object invoke(String action, Map<String, Object> args);
}
