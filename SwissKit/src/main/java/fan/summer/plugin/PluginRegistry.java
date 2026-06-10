package fan.summer.plugin;

import fan.summer.api.PluginContext;
import fan.summer.api.SwissKitJPlugin;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Holds the live plugin list and manages plugin activation lifecycle.
 *
 * <p>{@code PluginRegistry} is the central repository for all active plugins in the
 * application. It maintains an {@link ObservableList} of {@link SwissKitJPlugin}
 * instances which is the source of truth for the plugin UI sidebar. Built-in tools
 * are added directly via {@link #getPlugins()#addAll}; external JAR-based plugins are
 * added and removed by {@link PluginLoader}.</p>
 *
 * <p>The registry tracks a single <em>active</em> plugin at any time. When a new plugin
 * is activated via {@link #activate(SwissKitJPlugin)}, any previously active plugin is
 * automatically deactivated first by calling its {@link SwissKitJPlugin#onDeactivate()}
 * callback. This ensures that only one plugin's UI is interactive at a time.</p>
 *
 * <p>All lifecycle callbacks ({@code onActivate}, {@code onDeactivate}, {@code onUnload})
 * are wrapped in try-catch blocks to prevent a misbehaving plugin from crashing the
 * application. Exceptions are logged but otherwise ignored.</p>
 *
 * @see PluginLoader
 * @see SwissKitJPlugin
 * @since 1.0
 */
public class PluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(PluginRegistry.class);

    private static volatile PluginRegistry INSTANCE;

    private final ObservableList<SwissKitJPlugin> plugins =
        FXCollections.observableArrayList();

    private volatile SwissKitJPlugin activePlugin;

    private final Set<SwissKitJPlugin> backgroundPlugins =
        java.util.Collections.synchronizedSet(new LinkedHashSet<>());

    /**
     * Constructs a PluginRegistry and wires it to the given PluginLoader.
     *
     * <p>This constructor registers the newly created registry with the loader so that
     * the loader can call {@link #addPlugins} and {@link #removePlugin} on this instance.
     * It is called exactly once during application startup.</p>
     *
     * @param loader the PluginLoader to wire; must not be {@code null}
     * @since 1.0
     */
    public PluginRegistry(PluginLoader loader) {
        loader.setRegistry(this);
        INSTANCE = this;
    }

    public static PluginRegistry getInstance() {
        return INSTANCE;
    }

    // ── Plugin list ──────────────────────────────────────────────

    /**
     * Returns the observable list of all currently loaded plugins.
     *
     * <p>The returned list is the live backing list; adding or removing elements
     * directly is supported but should be done through {@link PluginLoader} for
     * external plugins or via {@link BuiltinToolRegistrar} for built-in tools.</p>
     *
     * @return the observable plugin list; never {@code null}
     * @since 1.0
     */
    public ObservableList<SwissKitJPlugin> getPlugins() {
        return plugins;
    }

    // Called by PluginLoader (already on FX thread via Platform.runLater)
    /**
     * Adds a collection of plugins to the registry.
     *
     * <p>This method is called by {@link PluginLoader} on the JavaFX Application Thread
     * (via {@code Platform.runLater}) when a new JAR is loaded. It appends the supplied
     * plugins to the live list.</p>
     *
     * @param toAdd the plugins to add; may be empty but not {@code null}
     * @since 1.0
     */
    void addPlugins(List<SwissKitJPlugin> toAdd) {
        log.debug("Adding {} plugin(s) to registry", toAdd.size());
        plugins.addAll(toAdd);
    }

    // Called by PluginLoader (already on FX thread via Platform.runLater)
    /**
     * Removes a plugin from the registry, invoking its
     * {@link SwissKitJPlugin#onDeactivate()} callback if it is currently active.
     *
     * <p>If the plugin being removed is the currently active one, it is deactivated
     * before removal. The {@code onUnload} callback is <em>not</em> called here —
     * it is already fired by {@link PluginLoader#unloadJar} before this method
     * is invoked, ensuring lifecycle callbacks fire exactly once.</p>
     *
     * @param plugin the plugin to remove; must be present in the registry
     * @since 1.0
     */
    void removePlugin(SwissKitJPlugin plugin) {
        log.debug("Removing plugin from registry: id={}", plugin.getId());
        backgroundPlugins.remove(plugin);
        if (activePlugin == plugin) {
            try {
                PluginContext.runWith(plugin, plugin::onDeactivate);
            } catch (Exception e) {
                log.warn("Plugin {} threw on onDeactivate(): {}", plugin.getId(), e.getMessage(), e);
            }
            activePlugin = null;
        }
        // Note: onUnload() is already called by PluginLoader.unloadJar(), so we
        // must not call it again here — lifecycle callbacks must fire exactly once.
        plugins.remove(plugin);
    }

    // ── Lifecycle ────────────────────────────────────────────────

    /**
     * Activates the given plugin, deactivating any previously active plugin first.
     *
     * <p>If another plugin is currently active, its {@link SwissKitJPlugin#onDeactivate()}
     * callback is invoked before the new plugin is activated. The newly activated plugin's
     * {@link SwissKitJPlugin#onActivate()} callback is then called. Both callbacks are
     * wrapped in try-catch: a misbehaving plugin will not prevent activation of the
     * replacement.</p>
     *
     * @param plugin the plugin to activate; must not be {@code null}
     * @since 1.0
     */
    public void activate(SwissKitJPlugin plugin) {
        boolean fromBackground = backgroundPlugins.remove(plugin);
        if (!fromBackground && activePlugin != null && activePlugin != plugin) {
            log.debug("Deactivating previous plugin: id={}", activePlugin.getId());
            try {
                PluginContext.runWith(activePlugin, activePlugin::onDeactivate);
            } catch (Exception e) {
                log.warn("Plugin {} threw on onDeactivate(): {}", activePlugin.getId(), e.getMessage(), e);
            }
        }
        activePlugin = plugin;
        log.info("Activating plugin: id={}, name={}", plugin.getId(), plugin.getName());
        try {
            PluginContext.runWith(plugin, plugin::onActivate);
        } catch (Exception e) {
            log.warn("Plugin {} threw on onActivate(): {}", plugin.getId(), e.getMessage(), e);
        }
        if (fromBackground) {
            log.debug("Plugin {} restored from background", plugin.getId());
            try {
                PluginContext.runWith(plugin, plugin::onForeground);
            } catch (Exception e) {
                log.warn("Plugin {} threw on onForeground(): {}", plugin.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Returns the currently active plugin, or {@code null} if no plugin is active.
     *
     * @return the active plugin, or {@code null}
     * @since 1.0
     */
    public SwissKitJPlugin getActivePlugin() {
        return activePlugin;
    }

    /**
     * Deactivates the currently active plugin, if any.
     *
     * <p>This calls {@link SwissKitJPlugin#onDeactivate()} on the active plugin and
     * clears the active plugin reference. It is a no-op if no plugin is currently active.
     * Exceptions thrown by the plugin callback are logged but otherwise ignored.</p>
     *
     * @since 1.0
     */
    public void deactivate() {
        if (activePlugin != null) {
            log.debug("Deactivating plugin: id={}", activePlugin.getId());
            if (activePlugin.hasRunningTasks()) {
                backgroundPlugins.add(activePlugin);
                try {
                    PluginContext.runWith(activePlugin, activePlugin::onBackground);
                } catch (Exception e) {
                    log.warn("Plugin {} threw on onBackground(): {}", activePlugin.getId(), e.getMessage(), e);
                }
            } else {
                try {
                    PluginContext.runWith(activePlugin, activePlugin::onDeactivate);
                } catch (Exception e) {
                    log.warn("Plugin {} threw on onDeactivate(): {}", activePlugin.getId(), e.getMessage(), e);
                }
            }
            activePlugin = null;
        }
    }

    /**
     * Returns whether the given plugin is currently running in the background.
     *
     * @param plugin the plugin to check
     * @return {@code true} if the plugin was backgrounded and has not been reactivated
     */
    public boolean isBackground(SwissKitJPlugin plugin) {
        return backgroundPlugins.contains(plugin);
    }

    /**
     * Finds a plugin by its ID.
     *
     * @param id the plugin reverse-domain ID
     * @return an Optional containing the plugin if found
     */
    public Optional<SwissKitJPlugin> findPlugin(String id) {
        return plugins.stream().filter(p -> p.getId().equals(id)).findFirst();
    }
}
