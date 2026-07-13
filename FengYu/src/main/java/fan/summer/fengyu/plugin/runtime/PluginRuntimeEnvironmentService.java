package fan.summer.fengyu.plugin.runtime;

import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.sdk.PluginEnvironment;
import fan.summer.fengyu.setup.DataSourceConfig;
import fan.summer.fengyu.setup.DataSourceConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** Builds the permission-gated process environment for an isolated plugin Worker. */
@Service
public class PluginRuntimeEnvironmentService {
    private final DataSourceConfigService dataSources;
    private final Path dataRoot;

    public PluginRuntimeEnvironmentService(DataSourceConfigService dataSources,
            @Value("${fengyu.plugins.data-directory:${user.home}/.fengyu/plugin-data}") String dataRoot) {
        this.dataSources = dataSources;
        this.dataRoot = Path.of(dataRoot).toAbsolutePath().normalize();
    }

    public Map<String, String> environmentFor(PluginManifest manifest) {
        if (manifest.permissions() == null || !manifest.permissions().contains("database")) {
            return Map.of();
        }
        DataSourceConfig config = dataSources.load();
        if (config == null) {
            throw new IllegalStateException("Host database is not configured");
        }

        Path pluginData = dataRoot.resolve(manifest.id()).normalize();
        if (!pluginData.startsWith(dataRoot)) {
            throw new IllegalArgumentException("Invalid plugin id for data directory");
        }
        try {
            Files.createDirectories(pluginData);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create plugin data directory", e);
        }

        return Map.of(
            PluginEnvironment.DB_TYPE, config.type().name().toLowerCase(Locale.ROOT),
            PluginEnvironment.DB_DRIVER, config.driver(),
            PluginEnvironment.DB_URL, config.url(),
            PluginEnvironment.DB_USERNAME, nullToEmpty(config.username()),
            PluginEnvironment.DB_PASSWORD, nullToEmpty(config.password()),
            PluginEnvironment.PLUGIN_DATA_DIR, pluginData.toString());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
