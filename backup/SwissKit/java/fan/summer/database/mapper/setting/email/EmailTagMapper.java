package fan.summer.database.mapper.setting.email;

import fan.summer.database.entity.setting.email.EmailTagEntity;

import java.util.List;

/**
 * Mapper interface for EmailTagEntity CRUD operations.
 * Provides database access methods for email tag management.
 *
 * @author phoebej
 */
public interface EmailTagMapper {
    /**
     * Inserts a new email tag into the database.
     *
     * @param emailTagEntity the tag entity to insert
     */
    void insert(EmailTagEntity emailTagEntity);

    /**
     * Updates an existing email tag in the database.
     *
     * @param emailTagEntity the tag entity with updated values
     */
    void update(EmailTagEntity emailTagEntity);

    /**
     * Retrieves all email tags from the database.
     *
     * @return a list of all email tag entities
     */
    List<EmailTagEntity> selectAll();
}
