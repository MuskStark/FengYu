package fan.summer.zhiflow.database.repository.email;

import fan.summer.zhiflow.database.entity.email.EmailMassSentConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailMassSentConfigRepository extends JpaRepository<EmailMassSentConfigEntity, Long> {
    Optional<EmailMassSentConfigEntity> findByUserIdAndTaskId(Long userId, String taskId);
}
