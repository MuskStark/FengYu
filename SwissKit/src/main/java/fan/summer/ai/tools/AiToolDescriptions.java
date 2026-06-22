package fan.summer.ai.tools;

import fan.summer.api.ai.AiServiceProvider;
import fan.summer.api.ai.AiTool;
import fan.summer.api.ai.AiToolParam;

import java.util.List;

/**
 * Picks the description and parameter list appropriate to the current backend mode.
 *
 * <p>Used by {@link ToolSchemaBuilder} (local path) and
 * {@code AiToolToToolSpecification} (cloud path) so each consumer renders the
 * version the active backend should see.</p>
 */
public final class AiToolDescriptions {

    private AiToolDescriptions() {}

    /** @return {@code true} when the active backend is the local GGUF engine. */
    private static boolean isLocalMode() {
        return "local".equals(AiServiceProvider.getCurrentMode());
    }

    /**
     * @return {@link AiTool#getLocalDescription()} in local mode,
     *         {@link AiTool#getDescription()} otherwise
     */
    public static String pickDescription(AiTool tool) {
        return isLocalMode() ? tool.getLocalDescription() : tool.getDescription();
    }

    /**
     * @return {@link AiTool#getLocalParameters()} in local mode,
     *         {@link AiTool#getParameters()} otherwise
     */
    public static List<AiToolParam> pickParameters(AiTool tool) {
        return isLocalMode() ? tool.getLocalParameters() : tool.getParameters();
    }
}
