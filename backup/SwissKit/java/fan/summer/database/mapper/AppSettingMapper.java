package fan.summer.database.mapper;

import fan.summer.database.entity.AppSettingEntity;

/**
 * Mapper for AppSettingEntity CRUD operations.
 *
 * @author summer
 */
public interface AppSettingMapper {
    /**
     * Selects a setting by key.
     *
     * @param key setting key
     * @return setting entity or null
     */
    AppSettingEntity selectByKey(String key);

    /**
     * Inserts a new setting.
     *
     * @param entity setting to insert
     */
    void insert(AppSettingEntity entity);

    /**
     * Updates an existing setting.
     *
     * @param entity setting with new value
     */
    void update(AppSettingEntity entity);
}