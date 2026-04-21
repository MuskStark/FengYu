package fan.summer.plugin.dto;

import lombok.Data;

/**
 * Metadata extracted from @SwissKitPage annotation during plugin deployment.
 */
@Data
public class PluginMetadata {
    private String jarName;
    private String pluginName;
    private String pluginVersion;
    private String menuName;
    private String menuTooltip;
    private String iconPath;
    private boolean visible;
    private int order;
    private String updateUrl;

    public PluginMetadata() {
    }

    public PluginMetadata(String jarName, String pluginName, String pluginVersion) {
        this.jarName = jarName;
        this.pluginName = pluginName;
        this.pluginVersion = pluginVersion;
    }
}
