package fan.summer.zhiflow.api.plugin;

import fan.summer.zhiflow.api.IconStyle;
import fan.summer.zhiflow.api.ToolCategory;

/**
 * Metadata for a v2 plugin. The {@code uiEntry} field points to the plugin's micro-frontend
 * ESM bundle (e.g. {@code "/plugin-ui/markdown/index.js"}), served by the backend.
 *
 * @param id unique reverse-domain ID (e.g. {@code "fan.summer.markdown"})
 * @param name display name
 * @param description short description
 * @param category tool category
 * @param icon MDI icon name (e.g. {@code "language-markdown"})
 * @param iconStyle icon background style
 * @param version semver string
 * @param uiEntry path to the ESM bundle entry (relative to backend root, or absolute URL)
 */
public record PluginDescriptor(
    String id,
    String name,
    String description,
    ToolCategory category,
    String icon,
    IconStyle iconStyle,
    String version,
    String uiEntry
) {}
