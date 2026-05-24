package fan.summer.database.mapper;

import fan.summer.database.entity.MenuOrderEntity;

import java.util.List;

/**
 * MyBatis mapper for {@link MenuOrderEntity} persistence operations.
 * <p>
 * Manages the sidebar menu ordering configuration, allowing bulk
 * operations to reset or replace the entire menu order sequence.
 *
 * @since 3.0.0
 * @see MenuOrderEntity
 */
public interface MenuOrderMapper {
    /**
     * Retrieves all menu order entries.
     *
     * @return a list of all menu order entities, ordered by {@code menuOrder}
     */
    List<MenuOrderEntity> selectAll();

    /**
     * Deletes all menu order entries.
     */
    void deleteAll();

    /**
     * Inserts multiple menu order entries in a batch operation.
     *
     * @param list the list of menu order entities to insert
     */
    void insertBatch(List<MenuOrderEntity> list);
}
