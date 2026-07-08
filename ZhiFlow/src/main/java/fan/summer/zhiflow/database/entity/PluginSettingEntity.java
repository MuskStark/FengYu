package fan.summer.zhiflow.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

/**
 * Entity for one plugin setting row: a key-value pair namespaced by plugin ID.
 * User-scoped — each user has independent plugin settings.
 *
 * @since 3.2.0
 */
@Entity
@Table(name = "plugin_setting",
        uniqueConstraints = @UniqueConstraint(name = "uk_plugin_setting_user",
                columnNames = {"user_id", "plugin_id", "setting_key"}))
@Data
public class PluginSettingEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "plugin_id", nullable = false)
    private String pluginId;

    @Column(name = "setting_key", nullable = false)
    private String settingKey;

    @Column(name = "setting_value", columnDefinition = "TEXT")
    private String settingValue;

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
