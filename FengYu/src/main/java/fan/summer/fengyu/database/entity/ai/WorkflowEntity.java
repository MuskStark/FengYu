package fan.summer.fengyu.database.entity.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/** A reusable workflow definition. Runs are stored separately in {@link AgentRunEntity}. */
@Entity
@Table(name = "ai_workflow",
        indexes = {
                @Index(name = "idx_workflow_user_updated", columnList = "user_id,updated_at"),
                @Index(name = "idx_workflow_published", columnList = "published")
        })
@Data
public class WorkflowEntity {
    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String description = "";

    @Column(name = "input_schema_json", columnDefinition = "TEXT", nullable = false)
    private String inputSchemaJson = "{\"type\":\"object\",\"properties\":{}}";

    @Column(name = "plan_json", columnDefinition = "TEXT", nullable = false)
    private String planJson;

    /**
     * Canvas node positions keyed by step index, so a saved graph reopens exactly as arranged.
     * Nullable on purpose: the column is added by {@code ddl-auto} to tables that may already
     * hold rows, and a NOT NULL ADD COLUMN would fail there; readers treat null as empty.
     */
    @Column(name = "layout_json", columnDefinition = "TEXT")
    private String layoutJson = "{}";

    /**
     * The raw canvas graph (nodes + edges + sticky notes) as authored in the flow builder —
     * the Flowise flowData equivalent. Nullable like {@code layout_json}: the column is added
     * by {@code ddl-auto} onto existing rows; readers treat null as "reconstruct from plan".
     */
    @Column(name = "graph_json", columnDefinition = "TEXT")
    private String graphJson;

    @Column(nullable = false)
    private boolean published;

    @Column(nullable = false)
    private int revision = 1;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
