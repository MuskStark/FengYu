package fan.summer.fengyu.api.plugin;

import fan.summer.fengyu.api.IconStyle;
import fan.summer.fengyu.api.ToolCategory;

/**
 * Metadata for a v2 plugin. The {@code uiEntry} field points to the plugin's micro-frontend
 * ESM bundle (e.g. {@code "/plugin-ui/markdown/index.js"}), served by the backend, and is
 * <strong>mandatory</strong> for every plugin.
 *
 * <p>{@code supportsAi} and {@code source} are declarative metadata: {@code supportsAi} only
 * advertises AI capability for UI affordances — the actual AI tools are registered separately
 * as Spring AI beans; {@code source} declares the plugin's origin (official vs. third-party)
 * for UI badges and optional trust checks.</p>
 *
 * @param id unique reverse-domain ID (e.g. {@code "fan.summer.markdown"})
 * @param name display name
 * @param description short description
 * @param category tool category
 * @param icon MDI icon name (e.g. {@code "language-markdown"})
 * @param iconStyle icon background style
 * @param version semver string
 * @param uiEntry mandatory path to the ESM bundle entry (relative to backend root, or absolute URL)
 * @param supportsAi declarative flag: whether this plugin advertises AI capability (actual AI
 *                  tools are Spring AI beans, not this flag)
 * @param source declared origin of the plugin (OFFICIAL / THIRD_PARTY)
 */
public record PluginDescriptor(
    String id,
    String name,
    String description,
    ToolCategory category,
    String icon,
    IconStyle iconStyle,
    String version,
    String uiEntry,
    boolean supportsAi,
    PluginSource source
) {}
