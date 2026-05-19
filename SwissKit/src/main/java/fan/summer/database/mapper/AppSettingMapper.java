package fan.summer.database.mapper;

import fan.summer.database.entity.AppSettingEntity;

public interface AppSettingMapper {
    AppSettingEntity selectByKey(String key);
    void insert(AppSettingEntity entity);
    void update(AppSettingEntity entity);
}
