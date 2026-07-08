package fan.summer.zhiflow.database.repository.setting.email;

import fan.summer.zhiflow.database.entity.setting.email.EmailTagEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmailTagRepository extends JpaRepository<EmailTagEntity, Long> {
    Optional<EmailTagEntity> findByUserIdAndTag(Long userId, String tag);
    List<EmailTagEntity> findAllByUserId(Long userId);
}
