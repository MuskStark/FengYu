package fan.summer.fengyu.database.repository.ai;

import fan.summer.fengyu.database.entity.ai.WorkflowScheduleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowScheduleRepository
        extends JpaRepository<WorkflowScheduleEntity, String> {
    List<WorkflowScheduleEntity> findByStatusOrderByCreatedAtAsc(String status);
    List<WorkflowScheduleEntity> findByClaimedAtIsNotNull();
    Optional<WorkflowScheduleEntity> findByIdAndUserId(String id, Long userId);
    List<WorkflowScheduleEntity> findByWorkflowIdAndUserIdAndStatus(
            String workflowId, Long userId, String status);
}
