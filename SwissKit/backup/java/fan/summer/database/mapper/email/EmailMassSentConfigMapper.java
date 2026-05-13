package fan.summer.database.mapper.email;

import fan.summer.database.entity.email.EmailMassSentConfigEntity;

import java.util.List;

/**
 * Mapper interface for EmailMassSentConfigEntity CRUD operations.
 *
 * @author summer
 * @version 1.00
 * @date 2026/3/11
 */
public interface EmailMassSentConfigMapper {

    /**
     * Inserts or updates a mass sent config record (upsert by task_id).
     *
     * @param config the entity to insert or update
     */
    void upsert(EmailMassSentConfigEntity config);

    /**
     * Selects a mass sent config record by task_id.
     *
     * @param taskId the task ID
     * @return the entity, or null if not found
     */
    EmailMassSentConfigEntity selectByTaskId(String taskId);

    /**
     * Selects all mass sent config records.
     *
     * @return list of all entities
     */
    List<EmailMassSentConfigEntity> selectAll();

    /**
     * Deletes a mass sent config record by task_id.
     *
     * @param taskId the task ID to delete
     */
    void deleteByTaskId(String taskId);

    /**
     * Deletes all mass sent config records.
     */
    void deleteAll();
}