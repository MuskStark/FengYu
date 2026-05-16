package fan.summer.database.mapper;

import fan.summer.database.entity.MenuOrderEntity;

import java.util.List;

public interface MenuOrderMapper {
    List<MenuOrderEntity> selectAll();
    void deleteAll();
    void insertBatch(List<MenuOrderEntity> list);
}
