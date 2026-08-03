package fan.summer.fengyu.database.entity.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A subscribed marketplace source (FengYu catalog, Claude marketplace, or Codex marketplace).
 *
 * @since 4.0.0
 */
@Entity
@Table(name = "store_sources",
        uniqueConstraints = @UniqueConstraint(name = "uk_store_source_origin", columnNames = "origin"))
@Data
public class StoreSourceEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "origin", nullable = false, unique = true)
    private String origin;

    @Column(name = "name", nullable = false)
    private String name;

    /** One of {@link fan.summer.fengyu.plugin.store.StoreSourceType} name(). */
    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "catalog_url", nullable = false)
    private String catalogUrl;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    @Column(name = "last_sync_ok")
    private boolean lastSyncOk;

    @Column(name = "last_error", length = 4000)
    private String lastError;

    @Column(name = "added_at")
    private LocalDateTime addedAt = LocalDateTime.now();

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
