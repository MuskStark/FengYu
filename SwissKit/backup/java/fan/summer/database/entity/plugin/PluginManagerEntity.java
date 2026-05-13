package fan.summer.database.entity.plugin;

import lombok.Data;
import java.sql.Timestamp;

/**
 * Plugin manager entity for tracking installed external plugins.
 *
 * @author summer
 * @version 1.00
 * @Date 2026/4/21
 */
@Data
public class PluginManagerEntity {
    private Integer id;
    private String jarName;
    private String pluginName;
    private String pluginVersion;
    private Integer isDisabled;
    private String updateUrl;
    private Timestamp lastCheck;
    private Timestamp installedAt;
}
