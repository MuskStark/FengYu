package fan.summer.database.entity;

import lombok.Data;

/**
 * Entity for application settings key-value store.
 *
 * @author summer
 */
@Data
public class AppSettingEntity {
    private Integer id;
    private String settingKey;
    private String settingValue;
}