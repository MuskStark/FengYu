package fan.summer.fengyu.database.repository.ai;

import fan.summer.fengyu.database.entity.ai.WorkflowWebhookTriggerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowWebhookTriggerRepository
        extends JpaRepository<WorkflowWebhookTriggerEntity, String> {
    List<WorkflowWebhookTriggerEntity> findByUserIdAndStatusOrderByCreatedAtAsc(
            Long userId, String status);
    Optional<WorkflowWebhookTriggerEntity> findByIdAndUserIdAndStatus(
            String id, Long userId, String status);
    List<WorkflowWebhookTriggerEntity> findByWorkflowIdAndUserIdAndStatus(
            String workflowId, Long userId, String status);
}
