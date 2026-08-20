package fan.summer.fengyu.database.repository.ai;

import fan.summer.fengyu.database.entity.ai.WorkflowWebhookDeliveryEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface WorkflowWebhookDeliveryRepository
        extends JpaRepository<WorkflowWebhookDeliveryEntity, String> {
    List<WorkflowWebhookDeliveryEntity> findByStatusInOrderByAcceptedAtAsc(
            Collection<String> statuses);
    long countByTriggerId(String triggerId);
    List<WorkflowWebhookDeliveryEntity> findByTriggerIdOrderByAcceptedAtDesc(
            String triggerId, Pageable pageable);

    /** Queue release wins only while the delivery is still queued. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update WorkflowWebhookDeliveryEntity delivery
               set delivery.status = 'SUBMITTED', delivery.taskId = :taskId
             where delivery.id = :id and delivery.status = 'QUEUED'
            """)
    int markSubmittedIfQueued(@Param("id") String id, @Param("taskId") String taskId);

    /** First terminal writer wins; late completion/cancellation callbacks cannot rewrite it. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update WorkflowWebhookDeliveryEntity delivery
               set delivery.status = :status,
                   delivery.completedAt = :completedAt,
                   delivery.error = :error
             where delivery.id = :id
               and delivery.status in ('CLAIMED', 'QUEUED', 'SUBMITTED')
            """)
    int finishIfActive(@Param("id") String id, @Param("status") String status,
                       @Param("completedAt") Instant completedAt,
                       @Param("error") String error);
}
