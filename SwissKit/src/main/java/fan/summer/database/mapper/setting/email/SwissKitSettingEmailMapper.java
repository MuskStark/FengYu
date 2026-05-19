package fan.summer.database.mapper.setting.email;

import fan.summer.database.entity.setting.email.SwissKitSettingEmailEntity;

public interface SwissKitSettingEmailMapper {
    void insert(SwissKitSettingEmailEntity user);
    SwissKitSettingEmailEntity selectLatest();
    void deleteAll();
}
