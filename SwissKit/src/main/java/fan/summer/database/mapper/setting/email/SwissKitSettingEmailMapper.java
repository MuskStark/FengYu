package fan.summer.database.mapper.setting.email;

import fan.summer.database.entity.setting.email.SwissKitSettingEmailEntity;

/**
 * Mapper interface for SwissKitSettingEmailEntity CRUD operations.
 *
 * @author summer
 * @version 1.00
 * @date 2026/3/1
 */
public interface SwissKitSettingEmailMapper {

    /**
     * Inserts a new email setting record.
     *
     * @param user the entity to insert
     */
    void insert(SwissKitSettingEmailEntity user);

    SwissKitSettingEmailEntity selectLatest();

    /**
     * Deletes all email setting records.
     */
    void deleteAll();
}
