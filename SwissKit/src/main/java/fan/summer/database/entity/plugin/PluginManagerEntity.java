package fan.summer.database.entity.plugin;

import lombok.Data;
import java.sql.Timestamp;

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
