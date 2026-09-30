package fan.summer.fengyu.database.repository.ai;

import fan.summer.fengyu.database.entity.ai.WorkflowEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowRepository extends JpaRepository<WorkflowEntity, String> {
    List<WorkflowEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);
    Optional<WorkflowEntity> findByIdAndUserId(String id, Long userId);
    List<WorkflowEntity> findByUserIdAndPublishedTrueOrderByUpdatedAtDesc(Long userId);
}
