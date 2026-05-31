package fan.summer.database.mapper.setting.email;

import fan.summer.database.entity.setting.email.EmailAddressBookEntity;

import java.util.List;

/**
 * MyBatis mapper interface for {@link fan.summer.database.entity.setting.email.EmailAddressBookEntity}.
 *
 * <p>Provides CRUD operations and a count query for email address book entries.
 *
 * @see fan.summer.database.entity.setting.email.EmailAddressBookEntity
 */
public interface EmailAddressBookMapper {
    /**
     * Retrieves all email address book entries.
     *
     * @return list of all EmailAddressBookEntity records
     */
    List<EmailAddressBookEntity> selectEmailAddressBook();

    /**
     * Inserts a new email address book entry.
     *
     * @param emailAddressBookEntity the entry to insert
     */
    void insert(EmailAddressBookEntity emailAddressBookEntity);

    /**
     * Updates an existing email address book entry.
     *
     * @param emailAddressBookEntity the entry with updated values; must include the primary key
     */
    void update(EmailAddressBookEntity emailAddressBookEntity);

    /**
     * Deletes an email address book entry by its primary key.
     *
     * @param id the primary key of the entry to delete
     */
    void deleteById(Integer id);

    /**
     * Counts how many email address book entries are associated with a given tag.
     *
     * @param tagId the tag primary key
     * @return the number of entries that reference the given tag
     */
    int countByTagId(Long tagId);
}
