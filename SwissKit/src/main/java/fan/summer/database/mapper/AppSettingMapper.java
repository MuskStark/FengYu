package fan.summer.database.mapper;

import fan.summer.database.entity.AppSettingEntity;

import java.util.List;

/**
 * MyBatis mapper for {@link AppSettingEntity} persistence operations.
 * <p>
 * Provides CRUD operations for application settings stored as key-value pairs.
 *
 * @since 3.0.0
 * @see AppSettingEntity
 */
public interface AppSettingMapper {
    /**
     * Retrieves a setting by its unique key.
     *
     * @param key the setting key to look up
     * @return the setting entity, or {@code null} if not found
     */
    AppSettingEntity selectByKey(String key);

    /**
     * Retrieves all settings from the database.
     *
     * @return a list of all setting entities; never null but may be empty
     */
    List<AppSettingEntity> selectAll();

    /**
     * Inserts a new setting into the database.
     *
     * @param entity the setting entity to insert
     */
    void insert(AppSettingEntity entity);

    /**
     * Updates an existing setting.
     *
     * @param entity the setting entity with updated values
     */
    void update(AppSettingEntity entity);
}
