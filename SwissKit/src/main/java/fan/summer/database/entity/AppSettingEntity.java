package fan.summer.database.entity;

import lombok.Data;

@Data
public class AppSettingEntity {
    private Integer id;
    private String settingKey;
    private String settingValue;
}
