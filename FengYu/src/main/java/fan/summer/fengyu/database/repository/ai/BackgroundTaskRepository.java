package fan.summer.fengyu.database.repository.ai;

import fan.summer.fengyu.database.entity.ai.BackgroundTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface BackgroundTaskRepository extends JpaRepository<BackgroundTaskEntity, String> {
    List<BackgroundTaskEntity> findByStatusInOrderByCreatedAtAsc(Collection<String> statuses);
    List<BackgroundTaskEntity> findByStatusNotInOrderByCreatedAtDesc(Collection<String> statuses);
}
