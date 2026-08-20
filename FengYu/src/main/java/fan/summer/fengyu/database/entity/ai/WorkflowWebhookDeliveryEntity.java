package fan.summer.fengyu.database.entity.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Data;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/** Hashed idempotency claim for one webhook event; raw event IDs are never retained. */
@Entity
@Table(name = "ai_workflow_webhook_delivery",
        indexes = {
                @Index(name = "idx_workflow_hook_delivery_trigger",
                        columnList = "trigger_id,accepted_at"),
                @Index(name = "idx_workflow_hook_delivery_status", columnList = "status")
        })
@Data
public class WorkflowWebhookDeliveryEntity implements Persistable<String> {
    /** Keyed events use {@code triggerId:eventHash}; unkeyed events use an opaque random suffix. */
    @Id
    @Column(length = 129)
    private String id;

    @Column(name = "trigger_id", nullable = false, length = 64)
    private String triggerId;

    @Column(name = "event_hash", nullable = false, length = 64)
    private String eventHash;

    /** Null on rows created before this field existed; those historical rows were all keyed. */
    @Column(name = "idempotency_key_present")
    private Boolean idempotencyKeyPresent;

    /** CLAIMED, QUEUED, SUBMITTED, COMPLETED, FAILED, CANCELLED, or INTERRUPTED. */
    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "task_id", length = 64)
    private String taskId;

    @Column(name = "accepted_at", nullable = false)
    private Instant acceptedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    /** Assigned IDs must use persist, not merge, so duplicate claims hit the unique key. */
    @Transient
    private boolean newEntity = true;

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        newEntity = false;
    }
}
