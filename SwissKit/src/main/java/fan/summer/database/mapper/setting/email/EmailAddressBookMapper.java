package fan.summer.database.mapper.setting.email;

import fan.summer.database.entity.setting.email.EmailAddressBookEntity;

import java.util.List;

public interface EmailAddressBookMapper {
    List<EmailAddressBookEntity> selectEmailAddressBook();
    void insert(EmailAddressBookEntity emailAddressBookEntity);
    void update(EmailAddressBookEntity emailAddressBookEntity);
}
