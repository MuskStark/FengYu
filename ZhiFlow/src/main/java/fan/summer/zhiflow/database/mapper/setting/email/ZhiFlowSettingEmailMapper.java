package fan.summer.zhiflow.database.mapper.setting.email;

import fan.summer.zhiflow.database.entity.setting.email.ZhiFlowSettingEmailEntity;

/**
 * MyBatis mapper for {@link ZhiFlowSettingEmailEntity} email configuration persistence.
 * <p>
 * Manages the application-wide SMTP email settings. Only a single
 * configuration is expected to exist in the database at any time.
 *
 * @since 3.0.0
 * @see ZhiFlowSettingEmailEntity
 */
public interface ZhiFlowSettingEmailMapper {
    /**
     * Inserts a new email configuration.
     *
     * @param user the email configuration entity to insert
     */
    void insert(ZhiFlowSettingEmailEntity user);

    /**
     * Retrieves the most recently inserted email configuration.
     *
     * @return the latest email configuration entity, or {@code null} if none exists
     */
    ZhiFlowSettingEmailEntity selectLatest();

    /**
     * Deletes all email configurations.
     */
    void deleteAll();
}
