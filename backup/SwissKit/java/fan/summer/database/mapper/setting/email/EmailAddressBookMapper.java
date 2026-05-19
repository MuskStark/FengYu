package fan.summer.database.mapper.setting.email;

import fan.summer.database.entity.setting.email.EmailAddressBookEntity;

import java.util.List;

/**
 * MyBatis Mapper interface for email address book operations.
 * Provides methods to query and insert email address entries.
 */
public interface EmailAddressBookMapper {
    /**
     * Retrieves all email address book entries from the database.
     *
     * @return list of all email address book entities
     */
    List<EmailAddressBookEntity> selectEmailAddressBook();

    /**
     * Inserts a new email address book entry into the database.
     *
     * @param emailAddressBookEntity the entity to insert
     */
    void insert(EmailAddressBookEntity emailAddressBookEntity);

    /**
     * Updates an existing email address book entry.
     *
     * @param emailAddressBookEntity the entity to update
     */
    void update(EmailAddressBookEntity emailAddressBookEntity);
}
