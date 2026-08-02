package fan.summer.fengyu.plugin.runtime;

import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.runtime.RuntimePaths;
import fan.summer.fengyu.log.LoggingLevelService;
import fan.summer.fengyu.setup.DataSourceConfig;
import fan.summer.fengyu.setup.DataSourceConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;

/** Builds the permission-gated process environment for an isolated plugin Worker. */
@Service
public class PluginRuntimeEnvironmentService {
    private final DataSourceConfigService dataSources;
    private final Path dataRoot;
    private final java.util.function.Supplier<String> logLevel;

    public PluginRuntimeEnvironmentService(DataSourceConfigService dataSources,
            @Value("${fengyu.plugins.data-directory:}") String dataRoot) {
        this(dataSources, dataRoot, () -> LoggingLevelService.DEFAULT_LEVEL);
    }

    @Autowired
    public PluginRuntimeEnvironmentService(DataSourceConfigService dataSources,
            @Value("${fengyu.plugins.data-directory:}") String dataRoot,
            LoggingLevelService logging) {
        this(dataSources, dataRoot, logging::currentLevel);
    }

    private PluginRuntimeEnvironmentService(DataSourceConfigService dataSources,
            String dataRoot, java.util.function.Supplier<String> logLevel) {
        this.dataSources = dataSources;
        this.dataRoot = dataRoot == null || dataRoot.isBlank()
                ? RuntimePaths.pluginDataDirectory(RuntimePaths.root())
                : Path.of(dataRoot).toAbsolutePath().normalize();
        this.logLevel = logLevel;
    }

    public Map<String, String> environmentFor(PluginManifest manifest) {
        Map<String, String> environment = new HashMap<>();
        environment.put(PluginWorkerProtocol.LOG_LEVEL_ENV, logLevel.get());
        Path pluginData = dataRoot.resolve(manifest.id()).normalize();
        if (!pluginData.startsWith(dataRoot)) {
            throw new IllegalArgumentException("Invalid plugin id for data directory");
        }
        try {
            Files.createDirectories(pluginData);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create plugin data directory", e);
        }
        environment.put(PluginWorkerProtocol.PLUGIN_DATA_DIR_ENV, pluginData.toString());

        if (manifest.permissions() == null || !manifest.permissions().contains("database")) {
            return Map.copyOf(environment);
        }
        DataSourceConfig config = dataSources.load();
        if (config == null) {
            throw new IllegalStateException("Host database is not configured");
        }

        environment.putAll(Map.of(
            PluginWorkerProtocol.DB_TYPE_ENV, config.type().name().toLowerCase(Locale.ROOT),
            PluginWorkerProtocol.DB_DRIVER_ENV, config.driver(),
            PluginWorkerProtocol.DB_URL_ENV, config.url(),
            PluginWorkerProtocol.DB_USERNAME_ENV, nullToEmpty(config.username()),
            PluginWorkerProtocol.DB_PASSWORD_ENV, nullToEmpty(config.password())));
        return Map.copyOf(environment);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
