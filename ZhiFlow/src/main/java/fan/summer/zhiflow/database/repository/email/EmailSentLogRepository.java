package fan.summer.zhiflow.database.repository.email;

import fan.summer.zhiflow.database.entity.email.EmailSentLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailSentLogRepository extends JpaRepository<EmailSentLogEntity, Long> {
}
