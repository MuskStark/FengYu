package fan.summer.zhiflow.database.entity.plugin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.sql.Timestamp;

/**
 * Entity representing a plugin managed by the built-in plugin manager.
 * Global table (no user_id) — installed plugins are system-wide, shared by all users.
 *
 * @since 3.0.0
 */
@Entity
@Table(name = "plugin_manager")
@Data
public class PluginManagerEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "jar_name", nullable = false, unique = true)
    private String jarName;

    @Column(name = "plugin_name", nullable = false)
    private String pluginName;

    @Column(name = "plugin_version", nullable = false)
    private String pluginVersion;

    @Column(name = "is_disabled", nullable = false)
    private Integer isDisabled;

    @Column(name = "update_url")
    private String updateUrl;

    @Column(name = "last_check")
    private Timestamp lastCheck;

    @Column(name = "installed_at")
    private Timestamp installedAt;
}
