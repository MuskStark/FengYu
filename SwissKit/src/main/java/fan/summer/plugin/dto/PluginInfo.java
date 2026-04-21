package fan.summer.plugin.dto;

import lombok.Data;

/**
 * Unified plugin information combining in-memory and database state.
 */
@Data
public class PluginInfo {
    private String jarName;
    private String pluginName;
    private String pluginVersion;
    private boolean isEnabled;
    private boolean isLoaded;
    private boolean isInstalled;
    private String updateUrl;
    private PluginUpdateInfo updateInfo;

    public PluginInfo() {
    }

    public PluginInfo(String jarName, String pluginName, String pluginVersion) {
        this.jarName = jarName;
        this.pluginName = pluginName;
        this.pluginVersion = pluginVersion;
        this.isEnabled = true;
        this.isLoaded = false;
        this.isInstalled = false;
    }

    public boolean hasUpdate() {
        return updateInfo != null && updateInfo.isHasUpdate();
    }
}
