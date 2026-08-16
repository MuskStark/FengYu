package fan.summer.fengyu.database.entity.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/** One durable cross-session memory statement (experimental feature; off by default). */
@Entity
@Table(name = "ai_memory",
        indexes = {
                @Index(name = "idx_memory_user_created", columnList = "user_id,created_at")
        })
@Data
public class AiMemoryEntity {
    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /** JSON array of topic strings. */
    @Column(name = "topics_json", columnDefinition = "TEXT", nullable = false)
    private String topicsJson = "[]";

    /** Where the entry came from: manual (memory_remember) today; auto-summaries later. */
    @Column(nullable = false, length = 32)
    private String source = "manual";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_accessed_at", nullable = false)
    private LocalDateTime lastAccessedAt;
}
