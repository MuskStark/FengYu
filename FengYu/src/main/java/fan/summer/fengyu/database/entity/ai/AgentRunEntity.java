package fan.summer.fengyu.database.entity.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable snapshot of one agent run. JSON columns keep the workflow contract portable. */
@Entity
@Table(name = "ai_agent_run",
        indexes = {
                @Index(name = "idx_agent_run_user_updated", columnList = "user_id,updated_at"),
                @Index(name = "idx_agent_run_status", columnList = "status")
        })
@Data
public class AgentRunEntity {
    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String goal = "";

    @Column(nullable = false, length = 40)
    private String status;

    @Column(name = "config_json", columnDefinition = "TEXT", nullable = false)
    private String configJson = "{}";

    @Column(name = "plan_json", columnDefinition = "TEXT")
    private String planJson;

    @Column(name = "executions_json", columnDefinition = "TEXT", nullable = false)
    private String executionsJson = "[]";

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * Plugin sandbox posture at create time ("sandboxed"/"unsandboxed"). Resume/fork
     * refuses to replay a sandboxed run while the host now runs unsandboxed — the
     * isolation a run was recorded under must not silently weaken.
     */
    @Column(name = "sandbox_profile", length = 32)
    private String sandboxProfile;

    @Column(name = "resumed_from", length = 64)
    private String resumedFrom;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
