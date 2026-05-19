package fan.summer.plugin.dto;

import java.util.List;

/**
 * Result wrapper for plugin deployment operation.
 */
public class DeployResult {
    private final List<Object> pages;
    private final PluginMetadata metadata;

    public DeployResult(List<Object> pages, PluginMetadata metadata) {
        this.pages = pages;
        this.metadata = metadata;
    }

    public List<Object> getPages() {
        return pages;
    }

    public PluginMetadata getMetadata() {
        return metadata;
    }
}
