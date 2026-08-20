package fan.summer.fengyu.database.entity.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/** Durable local-webhook binding for one published workflow. */
@Entity
@Table(name = "ai_workflow_webhook_trigger",
        indexes = {
                @Index(name = "idx_workflow_hook_owner_status",
                        columnList = "user_id,status,created_at"),
                @Index(name = "idx_workflow_hook_workflow",
                        columnList = "workflow_id,user_id,status")
        })
@Data
public class WorkflowWebhookTriggerEntity {
    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "workflow_id", nullable = false, length = 64)
    private String workflowId;

    @Column(nullable = false, length = 160)
    private String name;

    /** SHA-256 digest of the one-time-displayed trigger secret. */
    @Column(name = "secret_hash", nullable = false, length = 64)
    private String secretHash;

    @Column(name = "default_inputs_json", columnDefinition = "TEXT", nullable = false)
    private String defaultInputsJson = "{}";

    @Column(name = "permission_mode", nullable = false, length = 32)
    private String permissionMode;

    @Column(name = "sandbox_profile", nullable = false, length = 32)
    private String sandboxProfile;

    /** ACTIVE or CANCELLED. */
    @Column(nullable = false, length = 24)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_fire_at")
    private Instant lastFireAt;

    @Column(name = "last_task_id", length = 64)
    private String lastTaskId;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(nullable = false)
    private int fires;
}
