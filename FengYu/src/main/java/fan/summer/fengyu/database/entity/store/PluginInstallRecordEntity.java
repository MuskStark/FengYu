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
 * Persistent record of an installed plugin from any source. The source of truth for
 * Claude/Codex installs (which have no on-disk manifest to scan).
 *
 * @since 4.0.0
 */
@Entity
@Table(name = "plugin_install_records",
        uniqueConstraints = @UniqueConstraint(name = "uk_install_uid_user",
                columnNames = {"uid", "user_id"}))
@Data
public class PluginInstallRecordEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "uid", nullable = false)
    private String uid;

    @Column(name = "plugin_name", nullable = false)
    private String pluginName;

    /** One of {@link fan.summer.fengyu.plugin.store.StoreSourceType} name(). */
    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "origin", nullable = false)
    private String origin;

    @Column(name = "version")
    private String version;

    @Column(name = "pinned_sha")
    private String pinnedSha;

    @Column(name = "install_path", nullable = false)
    private String installPath;

    /** JSON array of declared skill paths (for uninstall cleanup). */
    @Column(name = "declared_skills", length = 8000)
    private String declaredSkills;

    /** JSON array of mcp server config file references (for uninstall cleanup). */
    @Column(name = "mcp_server_refs", length = 8000)
    private String mcpServerRefs;

    @Column(name = "has_mcp_servers", nullable = false)
    private boolean hasMcpServers;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "installed_at")
    private LocalDateTime installedAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
