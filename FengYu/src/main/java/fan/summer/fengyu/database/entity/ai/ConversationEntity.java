package fan.summer.fengyu.database.entity.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A saved AI chat conversation (the sidebar history entry). User-scoped.
 *
 * <p>Messages live in {@link ChatMessageEntity}, linked by {@code conversation_id}. The schema is
 * created by Hibernate {@code ddl-auto=update} from this entity, so it is portable across every
 * configured {@code DbType} (H2 / SQLite / MySQL / PostgreSQL) — no hand-written dialect SQL.
 *
 * @since 4.0.0
 */
@Entity
@Table(name = "ai_conversation",
        // The sidebar's hottest query is per-user, most-recently-updated first; every
        // sibling AI entity carries the same composite user index. Additive index (the
        // ChatMessageEntity convention) — ddl-auto=update creates it on existing installs,
        // which a Flyway SQL migration could not do portably (MySQL has no CREATE INDEX
        // IF NOT EXISTS, and Flyway runs before Hibernate creates the fresh-install table).
        indexes = @Index(name = "idx_ai_conversation_user_updated", columnList = "user_id,updated_at"))
@Data
public class ConversationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title = "";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Canonical workspace root attached by the user (coding-agent mode); null for ordinary
     * conversations. Set/cleared by {@code WorkspaceService}; added in 4.1.0 via ddl-auto.
     */
    @Column(name = "workspace_root", length = 1024)
    private String workspaceRoot;

    /** Sidebar pin (sticky at the top of its group); null/unset means unpinned. @since 4.1.0 */
    @Column(name = "pinned")
    private Boolean pinned;

    /** Archive timestamp; null while the conversation is active. @since 4.1.0 */
    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
