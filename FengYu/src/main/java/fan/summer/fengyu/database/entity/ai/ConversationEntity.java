package fan.summer.fengyu.database.entity.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
@Table(name = "ai_conversation")
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

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
