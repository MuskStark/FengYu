package fan.summer.database.mapper.email;

import fan.summer.database.entity.email.EmailMassSentConfigEntity;

import java.util.List;

/**
 * MyBatis mapper for {@link EmailMassSentConfigEntity} mass email configuration persistence.
 * <p>
 * Manages mass email sending configurations that define recipient lists,
 * template content, and scheduling parameters for bulk email operations.
 *
 * @since 3.0.0
 * @see EmailMassSentConfigEntity
 */
public interface EmailMassSentConfigMapper {
    /**
     * Inserts or updates (upsert) a mass email configuration.
     * If a configuration with the same task ID exists, it will be updated;
     * otherwise, a new record is inserted.
     *
     * @param config the configuration entity to upsert
     */
    void upsert(EmailMassSentConfigEntity config);

    /**
     * Retrieves a mass email configuration by its task ID.
     *
     * @param taskId the unique task identifier
     * @return the configuration entity, or {@code null} if not found
     */
    EmailMassSentConfigEntity selectByTaskId(String taskId);

    /**
     * Retrieves all mass email configurations.
     *
     * @return a list of all configuration entities
     */
    List<EmailMassSentConfigEntity> selectAll();

    /**
     * Deletes a mass email configuration by task ID.
     *
     * @param taskId the task ID of the configuration to delete
     */
    void deleteByTaskId(String taskId);

    /**
     * Deletes all mass email configurations.
     */
    void deleteAll();
}
