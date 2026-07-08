package fan.summer.zhiflow.database.entity;

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
 * Entity representing a favorited (bookmarked) plugin. User-scoped.
 *
 * @since 3.0.0
 */
@Entity
@Table(name = "plugin_favorites",
        uniqueConstraints = @UniqueConstraint(name = "uk_plugin_fav_user_plugin",
                columnNames = {"user_id", "plugin_id"}))
@Data
public class PluginFavoriteEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "plugin_id", nullable = false)
    private String pluginId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
