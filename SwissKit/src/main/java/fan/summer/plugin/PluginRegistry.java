package fan.summer.plugin;

import fan.summer.api.SwissKitJPlugin;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Holds the live plugin list and manages plugin activation lifecycle.
 * Built-in tools are added directly via getPlugins().addAll();
 * external JAR plugins are added/removed by PluginLoader.
 */
public class PluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(PluginRegistry.class);

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
        log.debug("Adding {} plugin(s) to registry", toAdd.size());
        plugins.addAll(toAdd);
    }

    // Called by PluginLoader (already on FX thread via Platform.runLater)
    void removePlugin(SwissKitJPlugin plugin) {
        log.debug("Removing plugin from registry: id={}", plugin.getId());
        if (activePlugin == plugin) {
            try {
                plugin.onDeactivate();
            } catch (Exception e) {
                log.warn("Plugin {} threw on onDeactivate(): {}", plugin.getId(), e.getMessage(), e);
            }
            activePlugin = null;
        }
        try {
            plugin.onUnload();
        } catch (Exception e) {
            log.warn("Plugin {} threw on onUnload(): {}", plugin.getId(), e.getMessage(), e);
        }
        plugins.remove(plugin);
    }

    // ── Lifecycle ────────────────────────────────────────────────

    /**
     * Activate a plugin: deactivates the currently active one first.
     */
    public void activate(SwissKitJPlugin plugin) {
        if (activePlugin != null && activePlugin != plugin) {
            log.debug("Deactivating previous plugin: id={}", activePlugin.getId());
            try {
                activePlugin.onDeactivate();
            } catch (Exception e) {
                log.warn("Plugin {} threw on onDeactivate(): {}", activePlugin.getId(), e.getMessage(), e);
            }
        }
        activePlugin = plugin;
        log.info("Activating plugin: id={}, name={}", plugin.getId(), plugin.getName());
        try {
            plugin.onActivate();
        } catch (Exception e) {
            log.warn("Plugin {} threw on onActivate(): {}", plugin.getId(), e.getMessage(), e);
        }
    }

    public SwissKitJPlugin getActivePlugin() {
        return activePlugin;
    }

    public void deactivate() {
        if (activePlugin != null) {
            log.debug("Deactivating plugin: id={}", activePlugin.getId());
            try {
                activePlugin.onDeactivate();
            } catch (Exception e) {
                log.warn("Plugin {} threw on onDeactivate(): {}", activePlugin.getId(), e.getMessage(), e);
            }
            activePlugin = null;
        }
    }
}
