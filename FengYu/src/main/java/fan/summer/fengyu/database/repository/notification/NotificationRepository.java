package fan.summer.fengyu.database.repository.notification;

import fan.summer.fengyu.database.entity.notification.NotificationEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {

    /** Newest-first page for the notification center list and the retention overflow fetch. */
    List<NotificationEntity> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);

    /** Oldest-first unread set for mark-all-read (bounded by the retention limit). */
    List<NotificationEntity> findByUserIdAndReadAtIsNullOrderByCreatedAtAsc(Long userId);

    long countByUserId(Long userId);

    long countByUserIdAndReadAtIsNull(Long userId);

    Optional<NotificationEntity> findByIdAndUserId(Long id, Long userId);
}
