package fan.summer.plugin;

import fan.summer.api.SwissKitJPlugin;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.List;

/**
 * Holds the live plugin list and manages plugin activation lifecycle.
 * Built-in tools are added directly via getPlugins().addAll();
 * external JAR plugins are added/removed by PluginLoader.
 */
public class PluginRegistry {

    private final ObservableList<SwissKitJPlugin> plugins =
        FXCollections.observableArrayList();

    private SwissKitJPlugin activePlugin;

    public PluginRegistry(PluginLoader loader) {
        loader.setRegistry(this);
    }

    // ── Plugin list ──────────────────────────────────────────────

    public ObservableList<SwissKitJPlugin> getPlugins() {
        return plugins;
    }

    // Called by PluginLoader (already on FX thread via Platform.runLater)
    void addPlugins(List<SwissKitJPlugin> toAdd) {
        plugins.addAll(toAdd);
    }

    // Called by PluginLoader (already on FX thread via Platform.runLater)
    void removePlugin(SwissKitJPlugin plugin) {
        if (activePlugin == plugin) {
            plugin.onDeactivate();
            activePlugin = null;
        }
        plugin.onUnload();
        plugins.remove(plugin);
    }

    // ── Lifecycle ────────────────────────────────────────────────

    /**
     * Activate a plugin: deactivates the currently active one first.
     */
    public void activate(SwissKitJPlugin plugin) {
        if (activePlugin != null && activePlugin != plugin) {
            activePlugin.onDeactivate();
        }
        activePlugin = plugin;
        plugin.onActivate();
    }

    public SwissKitJPlugin getActivePlugin() {
        return activePlugin;
    }

    public void deactivate() {
        if (activePlugin != null) {
            activePlugin.onDeactivate();
            activePlugin = null;
        }
    }
}
