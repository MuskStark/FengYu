package fan.summer.plugin.host;

import fan.summer.api.SwissKitJPlugin;
import fan.summer.api.host.BasePluginHost;
import fan.summer.api.host.PluginSettings;

/**
 * Host-side {@link fan.summer.api.host.PluginHost}: {@link BasePluginHost}
 * plus H2-backed settings.
 *
 * @since 3.2.0
 */
public class DefaultPluginHost extends BasePluginHost {

    private final PluginSettings settings;

    /**
     * @param plugin the plugin this host serves
     */
    public DefaultPluginHost(SwissKitJPlugin plugin) {
        super(plugin);
        this.settings = new H2PluginSettings(plugin.getId());
    }

    @Override
    public PluginSettings settings() {
        return settings;
    }
}
