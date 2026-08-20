package fan.summer.fengyu.database.repository.ai;

import fan.summer.fengyu.database.entity.ai.WorkflowRevisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowRevisionRepository extends JpaRepository<WorkflowRevisionEntity, String> {
    List<WorkflowRevisionEntity> findByWorkflowIdAndUserIdOrderByRevisionDesc(
            String workflowId, Long userId);

    Optional<WorkflowRevisionEntity> findByWorkflowIdAndUserIdAndRevision(
            String workflowId, Long userId, int revision);

    void deleteByWorkflowIdAndUserId(String workflowId, Long userId);
}
