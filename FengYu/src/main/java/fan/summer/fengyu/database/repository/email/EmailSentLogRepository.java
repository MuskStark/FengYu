package fan.summer.fengyu.database.repository.email;

import fan.summer.fengyu.database.entity.email.EmailSentLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailSentLogRepository extends JpaRepository<EmailSentLogEntity, Long> {
}
