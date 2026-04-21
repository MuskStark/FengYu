package fan.summer.plugin;

import fan.summer.database.entity.plugin.PluginManagerEntity;
import fan.summer.plugin.dto.DeployResult;
import fan.summer.plugin.dto.PluginInfo;
import fan.summer.plugin.dto.PluginMetadata;
import fan.summer.plugin.dto.PluginUpdateInfo;
import fan.summer.scaner.SwissKitPageScaner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Main facade service for plugin lifecycle management.
 * Coordinates between PluginLoader (JAR loading), PluginManager (DB persistence), and UI refresh.
 */
public class PluginService {

    private static final Logger logger = LoggerFactory.getLogger(PluginService.class);

    /**
     * Deploys a plugin JAR file and registers it in the database.
     *
     * @param jarFile the JAR file to deploy
     * @return PluginInfo with deployment details, or null if deployment failed
     */
    public static PluginInfo deployPlugin(File jarFile) {
        if (jarFile == null || !jarFile.exists()) {
            logger.warn("Invalid plugin file for deploy: {}", jarFile);
            return null;
        }

        String jarName = jarFile.getName();

        // Deploy and extract metadata via PluginLoader
        DeployResult result = PluginLoader.deployPluginWithMetadata(jarFile);
        PluginMetadata metadata = result.getMetadata();

        if (metadata.getPluginName() == null || metadata.getPluginName().isEmpty()) {
            metadata.setPluginName(jarName.replace(".jar", ""));
        }
        if (metadata.getPluginVersion() == null || metadata.getPluginVersion().isEmpty()) {
            metadata.setPluginVersion("1.0.0");
        }

        // Register in database
        PluginManager.registerPlugin(
                jarName,
                metadata.getPluginName(),
                metadata.getPluginVersion(),
                metadata.getUpdateUrl()
        );

        // Refresh UI
        SwissKitPageScaner.refreshPluginPages();

        // Build and return PluginInfo
        PluginInfo info = new PluginInfo(jarName, metadata.getPluginName(), metadata.getPluginVersion());
        info.setLoaded(true);
        info.setInstalled(true);
        info.setEnabled(true);
        info.setUpdateUrl(metadata.getUpdateUrl());

        logger.info("Plugin deployed successfully: {}", jarName);
        return info;
    }

    /**
     * Deploys a plugin from a path string.
     *
     * @param jarPath the path to the JAR file
     * @return PluginInfo with deployment details, or null if deployment failed
     */
    public static PluginInfo deployPlugin(String jarPath) {
        return deployPlugin(new File(jarPath));
    }

    /**
     * Uninstalls a plugin completely.
     * Unloads the JAR, unregisters from database, and deletes the JAR file.
     *
     * @param jarName the JAR file name
     * @return true if uninstallation was successful
     */
    public static boolean uninstallPlugin(String jarName) {
        // Unload from memory
        boolean unloaded = PluginLoader.unloadPlugin(jarName);
        if (!unloaded) {
            logger.warn("Plugin was not loaded in memory: {}", jarName);
        }

        // Unregister from database
        PluginManager.unregisterPlugin(jarName);

        // Delete JAR file
        File jarFile = new File(PluginLoader.PLUGIN_DIR, jarName);
        if (jarFile.exists()) {
            if (jarFile.delete()) {
                logger.info("Plugin JAR deleted: {}", jarName);
            } else {
                logger.warn("Failed to delete plugin JAR: {}", jarName);
            }
        }

        // Refresh UI
        SwissKitPageScaner.refreshPluginPages();

        logger.info("Plugin uninstalled: {}", jarName);
        return true;
    }

    /**
     * Hot-reloads an already-loaded plugin.
     *
     * @param jarName the JAR file name
     * @return PluginInfo with reloaded plugin details, or null if reload failed
     */
    public static PluginInfo reloadPlugin(String jarName) {
        File jarFile = new File(PluginLoader.PLUGIN_DIR, jarName);
        if (!jarFile.exists()) {
            logger.warn("Plugin JAR not found for reload: {}", jarName);
            return null;
        }

        // Get existing plugin info before reload
        PluginInfo existingInfo = getPluginInfo(jarName);

        // Reload via PluginLoader
        List<Object> pages = PluginLoader.reloadPlugin(jarName);
        if (pages.isEmpty()) {
            logger.warn("Plugin reload returned no pages: {}", jarName);
        }

        // Extract metadata
        DeployResult result = PluginLoader.deployPluginWithMetadata(jarFile);
        PluginMetadata metadata = result.getMetadata();

        // Refresh UI
        SwissKitPageScaner.refreshPluginPages();

        PluginInfo info = new PluginInfo(jarName, metadata.getPluginName(), metadata.getPluginVersion());
        info.setLoaded(true);
        info.setInstalled(true);
        info.setEnabled(existingInfo != null ? existingInfo.isEnabled() : true);
        info.setUpdateUrl(metadata.getUpdateUrl());

        logger.info("Plugin reloaded: {}", jarName);
        return info;
    }

    /**
     * Disables a plugin both in memory and in database.
     *
     * @param jarName the JAR file name
     * @return true if successful
     */
    public static boolean disablePlugin(String jarName) {
        // Disable in memory
        boolean memoryDisabled = PluginLoader.disablePlugin(jarName);
        // Disable in database
        boolean dbDisabled = PluginManager.disablePlugin(jarName);

        if (memoryDisabled || dbDisabled) {
            SwissKitPageScaner.refreshPluginPages();
            logger.info("Plugin disabled: {}", jarName);
            return true;
        }
        return false;
    }

    /**
     * Enables a plugin both in memory and in database.
     *
     * @param jarName the JAR file name
     * @return true if successful
     */
    public static boolean enablePlugin(String jarName) {
        // Enable in memory
        boolean memoryEnabled = PluginLoader.enablePlugin(jarName);
        // Enable in database
        boolean dbEnabled = PluginManager.enablePlugin(jarName);

        if (memoryEnabled || dbEnabled) {
            SwissKitPageScaner.refreshPluginPages();
            logger.info("Plugin enabled: {}", jarName);
            return true;
        }
        return false;
    }

    /**
     * Gets unified plugin information for a single plugin.
     *
     * @param jarName the JAR file name
     * @return PluginInfo combining memory and DB state, or null if not found
     */
    public static PluginInfo getPluginInfo(String jarName) {
        PluginInfo info = new PluginInfo();
        info.setJarName(jarName);

        // Get DB state
        List<PluginManagerEntity> dbPlugins = PluginManager.getAllPlugins();
        PluginManagerEntity dbEntity = dbPlugins.stream()
                .filter(p -> p.getJarName().equals(jarName))
                .findFirst()
                .orElse(null);

        if (dbEntity != null) {
            info.setPluginName(dbEntity.getPluginName());
            info.setPluginVersion(dbEntity.getPluginVersion());
            info.setEnabled(dbEntity.getIsDisabled() == 0);
            info.setUpdateUrl(dbEntity.getUpdateUrl());
        }

        // Get memory state
        PluginLoader.PluginState state = PluginLoader.getPluginState(jarName);
        info.setLoaded(state != null);
        if (state != null) {
            info.setEnabled(state.isEnabled());
        }

        // Check if JAR exists on disk
        File jarFile = new File(PluginLoader.PLUGIN_DIR, jarName);
        info.setInstalled(jarFile.exists());

        return info;
    }

    /**
     * Gets unified plugin information for all registered plugins.
     *
     * @return list of PluginInfo objects
     */
    public static List<PluginInfo> getAllPluginInfo() {
        List<PluginInfo> result = new ArrayList<>();

        // Get all plugins from DB
        List<PluginManagerEntity> dbPlugins = PluginManager.getAllPlugins();

        for (PluginManagerEntity dbEntity : dbPlugins) {
            PluginInfo info = new PluginInfo();
            info.setJarName(dbEntity.getJarName());
            info.setPluginName(dbEntity.getPluginName());
            info.setPluginVersion(dbEntity.getPluginVersion());
            info.setEnabled(dbEntity.getIsDisabled() == 0);
            info.setUpdateUrl(dbEntity.getUpdateUrl());

            // Get memory state
            PluginLoader.PluginState state = PluginLoader.getPluginState(dbEntity.getJarName());
            info.setLoaded(state != null);

            // Check if JAR exists on disk
            File jarFile = new File(PluginLoader.PLUGIN_DIR, dbEntity.getJarName());
            info.setInstalled(jarFile.exists());

            result.add(info);
        }

        return result;
    }

    /**
     * Gets list of JAR files installed on disk.
     *
     * @return list of installed plugin JAR files
     */
    public static List<File> getInstalledPlugins() {
        return PluginLoader.getInstalledPlugins();
    }

    /**
     * Gets list of currently loaded plugin JAR names.
     *
     * @return list of loaded plugin names
     */
    public static List<String> getLoadedPlugins() {
        return PluginLoader.getRegisteredPlugins();
    }

    /**
     * Checks all registered plugins for updates.
     *
     * @return list of update info for plugins with available updates
     */
    public static List<PluginUpdateInfo> checkForUpdates() {
        return PluginManager.checkAllForUpdates();
    }

    /**
     * Checks a single plugin for updates.
     *
     * @param jarName the JAR file name
     * @return update info or null if no update available
     */
    public static PluginUpdateInfo checkForUpdate(String jarName) {
        List<PluginManagerEntity> plugins = PluginManager.getAllPlugins();
        PluginManagerEntity plugin = plugins.stream()
                .filter(p -> p.getJarName().equals(jarName))
                .findFirst()
                .orElse(null);

        if (plugin == null) {
            return null;
        }

        return PluginManager.checkForUpdate(plugin);
    }

    /**
     * Updates a plugin to a new version.
     *
     * @param jarName the current JAR file name
     * @param newJarUrl URL to download the new JAR
     * @return true if update was successful
     */
    public static boolean updatePlugin(String jarName, String newJarUrl) {
        boolean updated = PluginManager.updatePlugin(jarName, newJarUrl);
        if (updated) {
            SwissKitPageScaner.refreshPluginPages();
        }
        return updated;
    }

    /**
     * Checks if a plugin is currently enabled.
     *
     * @param jarName the JAR file name
     * @return true if enabled
     */
    public static boolean isPluginEnabled(String jarName) {
        return PluginLoader.isPluginEnabled(jarName);
    }
}
