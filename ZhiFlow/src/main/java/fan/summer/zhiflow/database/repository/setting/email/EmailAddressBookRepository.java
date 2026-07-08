package fan.summer.zhiflow.database.repository.setting.email;

import fan.summer.zhiflow.database.entity.setting.email.EmailAddressBookEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmailAddressBookRepository extends JpaRepository<EmailAddressBookEntity, Integer> {
    List<EmailAddressBookEntity> findAllByUserId(Long userId);
}
