package fan.summer.database.mapper.plugin;

import fan.summer.database.entity.plugin.PluginManagerEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MyBatis mapper for {@link PluginManagerEntity} plugin management operations.
 * <p>
 * Provides comprehensive CRUD and query operations for managing the lifecycle
 * of plugins, including installation, enable/disable toggling, and update tracking.
 *
 * @since 3.0.0
 * @see PluginManagerEntity
 */
public interface PluginManagerMapper {
    /**
     * Retrieves all registered plugins.
     *
     * @return a list of all plugin entities
     */
    List<PluginManagerEntity> selectAll();

    /**
     * Retrieves all enabled plugins.
     *
     * @return a list of enabled plugin entities
     */
    List<PluginManagerEntity> selectEnabled();

    /**
     * Retrieves all disabled plugins.
     *
     * @return a list of disabled plugin entities
     */
    List<PluginManagerEntity> selectDisabled();

    /**
     * Retrieves a plugin by its JAR filename.
     *
     * @param jarName the JAR filename to look up
     * @return the plugin entity, or {@code null} if not found
     */
    PluginManagerEntity selectByJarName(@Param("jarName") String jarName);

    /**
     * Retrieves a plugin by its display name.
     *
     * @param pluginName the plugin display name to look up
     * @return the plugin entity, or {@code null} if not found
     */
    PluginManagerEntity selectByPluginName(@Param("pluginName") String pluginName);

    /**
     * Inserts a new plugin registration.
     *
     * @param entity the plugin entity to insert
     */
    void insert(PluginManagerEntity entity);

    /**
     * Updates the disabled status of a plugin.
     *
     * @param jarName the JAR filename of the plugin
     * @param isDisabled {@code 1} to disable, {@code 0} to enable
     */
    void updateDisabled(@Param("jarName") String jarName, @Param("isDisabled") Integer isDisabled);

    /**
     * Updates the version string of an installed plugin.
     *
     * @param jarName the JAR filename of the plugin
     * @param version the new version string
     */
    void updateVersion(@Param("jarName") String jarName, @Param("version") String version);

    /**
     * Updates the last update check timestamp for a plugin.
     *
     * @param jarName the JAR filename of the plugin
     */
    void updateLastCheck(@Param("jarName") String jarName);

    /**
     * Updates the update check URL for a plugin.
     *
     * @param jarName the JAR filename of the plugin
     * @param updateUrl the new update URL
     */
    void updateUpdateUrl(@Param("jarName") String jarName, @Param("updateUrl") String updateUrl);

    /**
     * Deletes a plugin registration by JAR filename.
     *
     * @param jarName the JAR filename of the plugin to delete
     */
    void deleteByJarName(@Param("jarName") String jarName);

    /**
     * Deletes a plugin registration by display name.
     *
     * @param pluginName the display name of the plugin to delete
     */
    void deleteByPluginName(@Param("pluginName") String pluginName);
}
