package fan.summer.database.mapper;

import fan.summer.database.entity.PluginSettingEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MyBatis mapper for {@link PluginSettingEntity} persistence.
 *
 * @since 3.2.0
 * @see PluginSettingEntity
 */
public interface PluginSettingMapper {

    /**
     * @param pluginId the plugin whose settings to load
     * @return all setting rows for the plugin; may be empty
     */
    List<PluginSettingEntity> selectByPluginId(@Param("pluginId") String pluginId);

    /**
     * Inserts or updates one setting (H2 MERGE on the (plugin_id, setting_key) key).
     *
     * @param entity the setting to upsert
     */
    void upsert(PluginSettingEntity entity);

    /**
     * @param pluginId   the owning plugin
     * @param settingKey the key to delete
     */
    void deleteByPluginIdAndKey(@Param("pluginId") String pluginId, @Param("settingKey") String settingKey);

    /**
     * Deletes every setting of the plugin (explicit uninstall only).
     *
     * @param pluginId the plugin to purge
     */
    void deleteByPluginId(@Param("pluginId") String pluginId);
}
