package fan.summer.database.mapper;

import fan.summer.database.entity.MenuOrderEntity;

import java.util.List;

/**
 * Mapper interface for MenuOrderEntity CRUD operations.
 * Provides database access for sidebar menu ordering.
 *
 * @author summer
 */
public interface MenuOrderMapper {
    /**
     * Retrieves all menu order records from the database.
     *
     * @return list of all menu order entities
     */
    List<MenuOrderEntity> selectAll();

    /**
     * Deletes all menu order records.
     */
    void deleteAll();

    /**
     * Batch inserts menu order records.
     *
     * @param list list of menu order entities to insert
     */
    void insertBatch(List<MenuOrderEntity> list);
}