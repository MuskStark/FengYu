package fan.summer.fengyu.database.entity.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/** Durable definition and delivery state for one scheduled workflow. */
@Entity
@Table(name = "ai_workflow_schedule",
        indexes = {
                @Index(name = "idx_workflow_schedule_owner_status",
                        columnList = "user_id,status,next_fire_at"),
                @Index(name = "idx_workflow_schedule_claimed", columnList = "claimed_at")
        })
@Data
public class WorkflowScheduleEntity {
    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "workflow_id", nullable = false, length = 64)
    private String workflowId;

    @Column(name = "inputs_json", columnDefinition = "TEXT", nullable = false)
    private String inputsJson = "{}";

    @Column(name = "interval_seconds", nullable = false)
    private int intervalSeconds;

    @Column(nullable = false)
    private boolean recurring;

    @Column(name = "permission_mode", nullable = false, length = 32)
    private String permissionMode;

    /** Plugin isolation posture captured at creation (sandboxed/unsandboxed). */
    @Column(name = "sandbox_profile", nullable = false, length = 32)
    private String sandboxProfile;

    /** ACTIVE, COMPLETED, EXPIRED, or CANCELLED. Terminal rows are retained for audit. */
    @Column(nullable = false, length = 24)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "next_fire_at", nullable = false)
    private Instant nextFireAt;

    /** Non-null between the durable at-most-once claim and task submission acknowledgement. */
    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "last_fire_at")
    private Instant lastFireAt;

    @Column(name = "last_task_id", length = 64)
    private String lastTaskId;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(nullable = false)
    private int fires;

    /** Extra overdue occurrences coalesced into a single immediate recovery fire. */
    @Column(name = "missed_fires", nullable = false)
    private int missedFires;
}
