package fan.summer.fengyu.database.entity.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable workflow snapshot created by an explicit publish action. */
@Entity
@Table(name = "ai_workflow_revision",
        indexes = {
                @Index(name = "idx_workflow_revision_owner",
                        columnList = "workflow_id,user_id,revision", unique = true)
        })
@Data
public class WorkflowRevisionEntity {
    @Id
    @Column(length = 96)
    private String id;

    @Column(name = "workflow_id", nullable = false, length = 64)
    private String workflowId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private int revision;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String description = "";

    @Column(name = "input_schema_json", columnDefinition = "TEXT", nullable = false)
    private String inputSchemaJson;

    @Column(name = "plan_json", columnDefinition = "TEXT", nullable = false)
    private String planJson;

    @Column(name = "layout_json", columnDefinition = "TEXT")
    private String layoutJson;

    @Column(name = "graph_json", columnDefinition = "TEXT")
    private String graphJson;

    @Column(name = "published_at", nullable = false)
    private LocalDateTime publishedAt;
}
