package fan.summer.fengyu.database.entity.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/** Durable owner-scoped snapshot of one queued, running, or finished host background task. */
@Entity
@Table(name = "ai_background_task",
        indexes = {
                @Index(name = "idx_background_task_owner_created",
                        columnList = "user_id,created_at"),
                @Index(name = "idx_background_task_status", columnList = "status")
        })
@Data
public class BackgroundTaskEntity {
    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 48)
    private String kind;

    /** Nullable for rows written before priority-aware scheduling was introduced. */
    @Column(length = 24)
    private String priority;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String output = "";

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
