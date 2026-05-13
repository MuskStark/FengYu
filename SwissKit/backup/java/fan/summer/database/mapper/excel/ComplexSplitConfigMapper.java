package fan.summer.database.mapper.excel;

import fan.summer.database.entity.excel.ComplexSplitConfigEntity;

import java.util.List;

/**
 * Mapper interface for ComplexSplitConfigEntity CRUD operations.
 *
 * @author summer
 * @version 1.00
 * @date 2026/3/3
 */
public interface ComplexSplitConfigMapper {

    /**
     * Inserts a new complex split configuration record.
     *
     * @param complexSplitConfigEntity the entity to insert
     */
    void insert(ComplexSplitConfigEntity complexSplitConfigEntity);

    /**
     * Updates an existing complex split configuration in the database.
     * Identifies the record by taskId and fieldName.
     *
     * @param complexSplitConfigEntity the entity with updated values
     */
    void update(ComplexSplitConfigEntity complexSplitConfigEntity);

    /**
     * Deletes all configuration records for a given task ID.
     *
     * @param taskId the task ID to delete records for
     */
    void deleteAllByTaskId(String taskId);

    /**
     * Retrieves all configuration records for a given task ID.
     *
     * @param taskId the task ID to query
     * @return a list of configuration entities for the specified task
     */
    List<ComplexSplitConfigEntity> selectAllByTaskId(String taskId);
}
