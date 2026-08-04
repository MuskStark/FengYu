package fan.summer.fengyu.plugin.runtime;

import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.runtime.RuntimePaths;
import fan.summer.fengyu.log.LoggingLevelService;
import fan.summer.fengyu.setup.DataSourceConfig;
import fan.summer.fengyu.setup.DataSourceConfigService;
import fan.summer.fengyu.setup.DbType;
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

        String workerDbUrl = resolveWorkerDbUrl(config, pluginData);
        environment.putAll(Map.of(
            PluginWorkerProtocol.DB_TYPE_ENV, config.type().name().toLowerCase(Locale.ROOT),
            PluginWorkerProtocol.DB_DRIVER_ENV, config.driver(),
            PluginWorkerProtocol.DB_URL_ENV, workerDbUrl,
            PluginWorkerProtocol.DB_USERNAME_ENV, nullToEmpty(config.username()),
            PluginWorkerProtocol.DB_PASSWORD_ENV, nullToEmpty(config.password())));
        return Map.copyOf(environment);
    }

    /**
     * Resolves the JDBC URL a database-permission worker should connect to.
     *
     * <p>For embedded databases (H2/SQLite) the host holds an exclusive file lock on its own DB
     * file, so a second process — especially a sandboxed one — cannot attach to the same file
     * (H2's {@code AUTO_SERVER} is defeated by the OS sandbox and the combination is rejected).
     * Each worker therefore gets its own DB file under its plugin data dir. Remote databases
     * (MySQL/PostgreSQL) are real servers that handle concurrent connections and pass through
     * unchanged.
     */
    private static String resolveWorkerDbUrl(DataSourceConfig config, Path pluginData) {
        if (!config.type().embedded) {
            return config.url();
        }
        // Embedded DB: give the worker its own file under its plugin data dir. Use the type's URL
        // template so the scheme/options stay correct, but with a per-plugin path. We deliberately
        // do NOT carry over AUTO_SERVER (or any host-specific option): the worker owns this file
        // exclusively and AUTO_SERVER is both useless and a sandbox blocker.
        String baseName = config.filePath() != null
            ? stripExtension(Path.of(config.filePath()).getFileName().toString())
            : "plugin";
        Path workerFile = pluginData.resolve(baseName);
        DbType type = config.type();
        // Drop any query options (;... for H2, ?... for others) from the template before substitution
        // so host-only options (e.g. AUTO_SERVER) never reach the worker.
        String template = type.urlTemplate.replaceAll("[;?].*$", "");
        return template.replace("{path}", workerFile.toString());
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
