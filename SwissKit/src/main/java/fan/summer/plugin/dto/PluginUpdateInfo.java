package fan.summer.plugin.dto;

import lombok.Data;

/**
 * Plugin update information DTO.
 */
@Data
public class PluginUpdateInfo {
    private String jarName;
    private String pluginName;
    private String currentVersion;
    private String latestVersion;
    private String downloadUrl;
    private String releaseNotes;
    private boolean hasUpdate;
}