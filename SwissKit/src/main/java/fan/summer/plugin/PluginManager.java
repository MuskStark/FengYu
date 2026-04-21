package fan.summer.plugin;

import fan.summer.database.DatabaseInit;
import fan.summer.database.entity.plugin.PluginManagerEntity;
import fan.summer.database.mapper.plugin.PluginManagerMapper;
import fan.summer.plugin.dto.PluginUpdateInfo;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for managing external plugins.
 * Coordinates between PluginLoader, database, and UI.
 */
public class PluginManager {

    private static final Logger logger = LoggerFactory.getLogger(PluginManager.class);

    private static final int UPDATE_CHECK_TIMEOUT_MS = 5000;

    /**
     * Registers a deployed plugin in the database.
     *
     * @param jarName the JAR file name
     * @param pluginName the plugin name from @SwissKitPage
     * @param pluginVersion the plugin version from @SwissKitPage
     * @param updateUrl the update check URL (can be null)
     */
    public static void registerPlugin(String jarName, String pluginName, String pluginVersion, String updateUrl) {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            PluginManagerMapper mapper = session.getMapper(PluginManagerMapper.class);

            // Check if already exists
            PluginManagerEntity existing = mapper.selectByJarName(jarName);
            if (existing != null) {
                logger.debug("Plugin already registered: {}", jarName);
                return;
            }

            PluginManagerEntity entity = new PluginManagerEntity();
            entity.setJarName(jarName);
            entity.setPluginName(pluginName);
            entity.setPluginVersion(pluginVersion);
            entity.setUpdateUrl(updateUrl);
            entity.setIsDisabled(0);

            mapper.insert(entity);
            session.commit();
            logger.info("Registered plugin in database: {}", jarName);
        } catch (Exception e) {
            logger.error("Failed to register plugin in database: {}", jarName, e);
        }
    }

    /**
     * Unregisters a plugin from the database.
     *
     * @param jarName the JAR file name
     */
    public static void unregisterPlugin(String jarName) {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            PluginManagerMapper mapper = session.getMapper(PluginManagerMapper.class);
            mapper.deleteByJarName(jarName);
            session.commit();
            logger.info("Unregistered plugin from database: {}", jarName);
        } catch (Exception e) {
            logger.error("Failed to unregister plugin from database: {}", jarName, e);
        }
    }

    /**
     * Disables a plugin (DB only - caller should also disable in memory via PluginLoader).
     *
     * @param jarName the JAR file name
     * @return true if successful
     */
    public static boolean disablePlugin(String jarName) {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            PluginManagerMapper mapper = session.getMapper(PluginManagerMapper.class);
            mapper.updateDisabled(jarName, 1);
            session.commit();
            logger.info("Plugin disabled in database: {}", jarName);
            return true;
        } catch (Exception e) {
            logger.error("Failed to update disabled state in database: {}", jarName, e);
            return false;
        }
    }

    /**
     * Enables a plugin (DB only - caller should also enable in memory via PluginLoader).
     *
     * @param jarName the JAR file name
     * @return true if successful
     */
    public static boolean enablePlugin(String jarName) {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            PluginManagerMapper mapper = session.getMapper(PluginManagerMapper.class);
            mapper.updateDisabled(jarName, 0);
            session.commit();
            logger.info("Plugin enabled in database: {}", jarName);
            return true;
        } catch (Exception e) {
            logger.error("Failed to update enabled state in database: {}", jarName, e);
            return false;
        }
    }

    /**
     * Checks if a plugin is enabled.
     *
     * @param jarName the JAR file name
     * @return true if enabled or not found in database
     */
    public static boolean isPluginEnabled(String jarName) {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            PluginManagerMapper mapper = session.getMapper(PluginManagerMapper.class);
            PluginManagerEntity entity = mapper.selectByJarName(jarName);
            // If not in DB, assume enabled
            return entity == null || entity.getIsDisabled() == 0;
        } catch (Exception e) {
            logger.warn("Failed to check plugin enabled state: {}", jarName, e);
            return true;
        }
    }

    /**
     * Gets all registered plugins from database.
     *
     * @return list of plugin entities
     */
    public static List<PluginManagerEntity> getAllPlugins() {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            PluginManagerMapper mapper = session.getMapper(PluginManagerMapper.class);
            return mapper.selectAll();
        } catch (Exception e) {
            logger.error("Failed to get all plugins", e);
            return new ArrayList<>();
        }
    }

    /**
     * Checks all plugins for updates.
     *
     * @return list of update info for plugins with available updates
     */
    public static List<PluginUpdateInfo> checkAllForUpdates() {
        List<PluginUpdateInfo> updates = new ArrayList<>();
        List<PluginManagerEntity> plugins = getAllPlugins();

        for (PluginManagerEntity plugin : plugins) {
            PluginUpdateInfo update = checkForUpdate(plugin);
            if (update != null && update.isHasUpdate()) {
                updates.add(update);
            }
        }
        return updates;
    }

    /**
     * Checks a single plugin for updates.
     *
     * @param plugin the plugin entity
     * @return update info or null if no update available
     */
    public static PluginUpdateInfo checkForUpdate(PluginManagerEntity plugin) {
        if (plugin.getUpdateUrl() == null || plugin.getUpdateUrl().isEmpty()) {
            return null;
        }

        try {
            URL url = new URL(plugin.getUpdateUrl());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(UPDATE_CHECK_TIMEOUT_MS);
            conn.setReadTimeout(UPDATE_CHECK_TIMEOUT_MS);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                String response = new String(conn.getInputStream().readAllBytes());
                return parseUpdateResponse(plugin, response);
            }
        } catch (Exception e) {
            logger.warn("Failed to check for updates for {}: {}", plugin.getPluginName(), e.getMessage());
        }
        return null;
    }

    /**
     * Parses update JSON response.
     * Expected format: {"latestVersion": "1.2.0", "downloadUrl": "...", "releaseNotes": "..."}
     */
    private static PluginUpdateInfo parseUpdateResponse(PluginManagerEntity plugin, String json) {
        try {
            // Simple JSON parsing without external library
            String latestVersion = extractJsonString(json, "latestVersion");
            String downloadUrl = extractJsonString(json, "downloadUrl");
            String releaseNotes = extractJsonString(json, "releaseNotes");

            if (latestVersion == null || downloadUrl == null) {
                return null;
            }

            boolean hasUpdate = compareVersions(plugin.getPluginVersion(), latestVersion) < 0;

            PluginUpdateInfo info = new PluginUpdateInfo();
            info.setJarName(plugin.getJarName());
            info.setPluginName(plugin.getPluginName());
            info.setCurrentVersion(plugin.getPluginVersion());
            info.setLatestVersion(latestVersion);
            info.setDownloadUrl(downloadUrl);
            info.setReleaseNotes(releaseNotes);
            info.setHasUpdate(hasUpdate);
            return info;
        } catch (Exception e) {
            logger.warn("Failed to parse update response for {}: {}", plugin.getPluginName(), e.getMessage());
            return null;
        }
    }

    /**
     * Extracts a string value from JSON.
     */
    private static String extractJsonString(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex < 0) return null;

        int colonIndex = json.indexOf(':', keyIndex);
        if (colonIndex < 0) return null;

        int startQuote = json.indexOf('"', colonIndex);
        if (startQuote < 0) return null;

        int endQuote = json.indexOf('"', startQuote + 1);
        if (endQuote < 0) return null;

        return json.substring(startQuote + 1, endQuote);
    }

    /**
     * Compares semantic versions.
     *
     * @return negative if v1 < v2, 0 if equal, positive if v1 > v2
     */
    public static int compareVersions(String v1, String v2) {
        if (v1 == null) v1 = "0";
        if (v2 == null) v2 = "0";

        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");

        int maxLen = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLen; i++) {
            int p1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
            int p2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;
            if (p1 != p2) {
                return p1 - p2;
            }
        }
        return 0;
    }

    private static int parseVersionPart(String part) {
        try {
            // Remove any non-digit suffix (e.g., "1-beta" -> "1")
            String numStr = part.split("[^0-9]")[0];
            return numStr.isEmpty() ? 0 : Integer.parseInt(numStr);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Updates a plugin to a new version.
     *
     * @param jarName the current JAR file name
     * @param newJarUrl URL to download the new JAR
     * @return true if update was successful
     */
    public static boolean updatePlugin(String jarName, String newJarUrl) {
        try {
            // Download new JAR to temp file
            Path tempFile = Files.createTempFile("plugin_update_", ".jar");
            URL url = new URL(newJarUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(UPDATE_CHECK_TIMEOUT_MS);
            conn.setReadTimeout(UPDATE_CHECK_TIMEOUT_MS);
            Files.copy(conn.getInputStream(), tempFile, StandardCopyOption.REPLACE_EXISTING);
            conn.disconnect();

            // Extract version from filename
            String newFileName = tempFile.getFileName().toString();
            String newVersion = extractVersionFromFileName(newFileName);

            // Unload and remove old plugin
            PluginLoader.unloadPlugin(jarName);
            unregisterPlugin(jarName);
            File oldFile = new File(PluginLoader.PLUGIN_DIR, jarName);
            if (oldFile.exists()) {
                oldFile.delete();
            }

            // Move temp file to plugin directory with new name
            File newFile = new File(PluginLoader.PLUGIN_DIR, newFileName);
            Files.move(tempFile, newFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            // Deploy new plugin
            PluginLoader.deployPlugin(newFile);
            registerPlugin(newFileName, extractPluginName(newFileName), newVersion, null);

            logger.info("Plugin updated: {} -> {}", jarName, newFileName);
            return true;
        } catch (Exception e) {
            logger.error("Failed to update plugin: {}", jarName, e);
            return false;
        }
    }

    private static String extractVersionFromFileName(String fileName) {
        // Try to extract version from filename like "MyPlugin-1.2.0.jar"
        int lastDash = fileName.lastIndexOf('-');
        int lastDot = fileName.lastIndexOf('.');
        if (lastDash > 0 && lastDot > lastDash) {
            return fileName.substring(lastDash + 1, lastDot);
        }
        return "unknown";
    }

    private static String extractPluginName(String fileName) {
        // Try to extract plugin name from filename like "MyPlugin-1.2.0.jar"
        int lastDash = fileName.lastIndexOf('-');
        int lastDot = fileName.lastIndexOf('.');
        if (lastDash > 0) {
            return fileName.substring(0, lastDash);
        }
        return fileName.replace(".jar", "");
    }
}