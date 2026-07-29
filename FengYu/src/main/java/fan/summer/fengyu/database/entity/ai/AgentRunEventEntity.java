package fan.summer.fengyu.database.entity.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable, ordered lifecycle event for reconnect, audit, and interrupted-run diagnosis. */
@Entity
@Table(name = "ai_agent_run_event",
        indexes = @Index(name = "idx_agent_event_run", columnList = "run_id,seq"),
        uniqueConstraints = @UniqueConstraint(
                name = "uk_agent_event_run_seq", columnNames = {"run_id", "seq"}))
@Data
public class AgentRunEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(nullable = false)
    private long seq;

    @Column(nullable = false, length = 48)
    private String type;

    @Column(name = "data_json", columnDefinition = "TEXT", nullable = false)
    private String dataJson = "{}";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
