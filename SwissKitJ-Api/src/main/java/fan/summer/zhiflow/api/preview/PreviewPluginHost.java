package fan.summer.zhiflow.api.preview;

import fan.summer.zhiflow.api.SwissKitJPlugin;
import fan.summer.zhiflow.api.host.BasePluginHost;
import fan.summer.zhiflow.api.host.PluginSettings;

/**
 * Preview-side {@link fan.summer.zhiflow.api.host.PluginHost}: {@link BasePluginHost}
 * plus properties-file settings, so plugins behave the same in the preview
 * window as inside the real host.
 *
 * @since 3.2.0
 */
class PreviewPluginHost extends BasePluginHost {

    private final PluginSettings settings;

    PreviewPluginHost(SwissKitJPlugin plugin) {
        super(plugin);
        this.settings = new PropertiesPluginSettings(plugin.getId());
    }

    @Override
    public PluginSettings settings() {
        return settings;
    }
}
