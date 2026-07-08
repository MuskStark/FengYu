package fan.summer.zhiflow.api.plugin;

import fan.summer.zhiflow.api.ai.AiTool;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Plugin contract v2 (headless) — backend logic only. UI is delivered as a separately-served
 * micro-frontend ESM bundle ({@link PluginDescriptor#uiEntry()}).
 * <p>
 * The old {@code ZhiFlowPlugin.createView()} → JavaFX {@code Node} contract is replaced by
 * {@code invoke(action, args)} → JSON (backend) + the ESM bundle (frontend).
 */
public interface ZhiFlowPluginV2 {

    /**
     * Returns plugin metadata including the {@code uiEntry} path to its micro-frontend bundle.
     */
    PluginDescriptor descriptor();

    /**
     * Generic backend invocation — the plugin's logic exposed as JSON-in / JSON-out RPC.
     * Actions are plugin-defined strings (e.g. {@code "render"}, {@code "encode"}).
     * Arguments are a flat JSON object. Returns a JSON-serializable result (Map, String, etc.).
     * <p>
     * Throws {@code IllegalArgumentException} if the action is unknown or args are invalid.
     *
     * @param action the action to perform
     * @param args the action arguments (JSON-deserialized map)
     * @return the result (will be JSON-serialized by the controller)
     */
    Object invoke(String action, Map<String, Object> args);

    /**
     * AI tools exposed by this plugin (same as v1 contract). Auto-registered with
     * {@code AiServiceProvider} when the plugin loads.
     */
    default List<AiTool> aiTools() {
        return Collections.emptyList();
    }
}
