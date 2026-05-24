package fan.summer.database.mapper.excel;

import fan.summer.database.entity.excel.ComplexSplitConfigEntity;
import java.util.List;

/**
 * MyBatis mapper for {@link ComplexSplitConfigEntity} complex Excel split operations.
 * <p>
 * Manages configurations for Excel splitting tasks that involve multiple output
 * files based on column grouping or selective sheet copying.
 *
 * @since 3.0.0
 * @see ComplexSplitConfigEntity
 */
public interface ComplexSplitConfigMapper {
    /**
     * Inserts a new complex split configuration.
     *
     * @param entity the configuration entity to insert
     */
    void insert(ComplexSplitConfigEntity entity);

    /**
     * Updates an existing complex split configuration.
     *
     * @param entity the configuration entity with updated values
     */
    void update(ComplexSplitConfigEntity entity);

    /**
     * Deletes all complex split configurations for a given task.
     *
     * @param taskId the task ID whose configurations should be deleted
     */
    void deleteAllByTaskId(String taskId);

    /**
     * Retrieves all complex split configurations for a given task.
     *
     * @param taskId the task ID to look up
     * @return a list of matching configuration entities
     */
    List<ComplexSplitConfigEntity> selectAllByTaskId(String taskId);
}
