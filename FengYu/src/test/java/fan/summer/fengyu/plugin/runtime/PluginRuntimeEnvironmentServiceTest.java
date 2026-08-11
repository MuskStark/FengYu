package fan.summer.fengyu.plugin.runtime;

import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.setup.DataSourceConfig;
import fan.summer.fengyu.setup.DataSourceConfigService;
import fan.summer.fengyu.setup.DbType;
import fan.summer.fengyu.setup.PluginDbProvisioningStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginRuntimeEnvironmentServiceTest {
    @TempDir Path temp;

    @Test
    void databasePermissionReceivesConnectionAndStableDataDirectory() {
        DataSourceConfigService dataSources = new DataSourceConfigService(temp.resolve("host").toString());
        dataSources.save(new DataSourceConfig(DbType.H2, "jdbc:h2:file:host", "org.h2.Driver",
            "org.hibernate.dialect.H2Dialect", "sa", "secret", null));
        PluginRuntimeEnvironmentService service =
            new PluginRuntimeEnvironmentService(dataSources, temp.resolve("plugin-data").toString());

        Map<String, String> first = service.environmentFor(manifest("fan.summer.email", List.of("database")));
        Map<String, String> second = service.environmentFor(manifest("fan.summer.email", List.of("database")));

        assertEquals("h2", first.get(PluginWorkerProtocol.DB_TYPE_ENV));
        // Embedded H2: the worker must NOT receive the host's URL (the host holds an exclusive file
        // lock on it). It gets its own DB file under its plugin data dir.
        String workerUrl = first.get(PluginWorkerProtocol.DB_URL_ENV);
        assertEquals("secret", first.get(PluginWorkerProtocol.DB_PASSWORD_ENV));
        assertEquals(first, second);
        assertTrue(Files.isDirectory(Path.of(first.get(PluginWorkerProtocol.PLUGIN_DATA_DIR_ENV))));
        assertTrue(workerUrl.startsWith("jdbc:h2:file:"),
            "embedded H2 worker URL must be file-based, was: " + workerUrl);
    }

    @Test
    void embeddedH2WorkerGetsItsOwnDbFileWithoutAutoServer() {
        // The host holds an exclusive file lock on its embedded H2 file; a sandboxed second process
        // cannot attach (AUTO_SERVER is defeated by the sandbox and H2 rejects the combination).
        // The worker must therefore receive its own DB file under its PLUGIN_DATA_DIR, and the URL
        // must not carry AUTO_SERVER (useless for a single-process worker pool and the source of the
        // misleading [50100-240] error).
        DataSourceConfigService dataSources = new DataSourceConfigService(temp.resolve("host").toString()) {
            @Override public DataSourceConfig load() {
                return new DataSourceConfig(DbType.H2, "jdbc:h2:file:/host/app;AUTO_SERVER=TRUE",
                    "org.h2.Driver", "org.hibernate.dialect.H2Dialect", "sa", "secret", "/host/app");
            }
        };
        PluginRuntimeEnvironmentService service =
            new PluginRuntimeEnvironmentService(dataSources, temp.resolve("plugin-data").toString());

        Map<String, String> env = service.environmentFor(manifest("fan.summer.email", List.of("database")));
        String workerUrl = env.get(PluginWorkerProtocol.DB_URL_ENV);
        String pluginDataDir = env.get(PluginWorkerProtocol.PLUGIN_DATA_DIR_ENV);

        assertFalse(workerUrl.contains("AUTO_SERVER"),
            "worker URL must not carry AUTO_SERVER: " + workerUrl);
        // The DB path must live under the worker's own plugin data dir, not the host's file.
        assertTrue(workerUrl.contains(pluginDataDir),
            "worker DB path must be under PLUGIN_DATA_DIR (" + pluginDataDir + "), was: " + workerUrl);
        assertNotEquals("jdbc:h2:file:/host/app;AUTO_SERVER=TRUE", workerUrl,
            "worker must not receive the host's URL verbatim");
    }

    @Test
    void embeddedSqliteWorkerGetsItsOwnDbFile() {
        DataSourceConfigService dataSources = new DataSourceConfigService(temp.resolve("host").toString()) {
            @Override public DataSourceConfig load() {
                return new DataSourceConfig(DbType.SQLITE, "jdbc:sqlite:/host/app.db",
                    "org.sqlite.JDBC", "org.hibernate.community.dialect.SQLiteDialect", "", "", "/host/app.db");
            }
        };
        PluginRuntimeEnvironmentService service =
            new PluginRuntimeEnvironmentService(dataSources, temp.resolve("plugin-data").toString());

        Map<String, String> env = service.environmentFor(manifest("fan.summer.email", List.of("database")));
        String workerUrl = env.get(PluginWorkerProtocol.DB_URL_ENV);
        String pluginDataDir = env.get(PluginWorkerProtocol.PLUGIN_DATA_DIR_ENV);

        assertTrue(workerUrl.startsWith("jdbc:sqlite:"),
            "sqlite worker URL must keep its scheme: " + workerUrl);
        assertTrue(workerUrl.contains(pluginDataDir),
            "worker DB path must be under PLUGIN_DATA_DIR, was: " + workerUrl);
    }

    @Test
    void provisionedRemoteDatabaseWorkerReceivesIsolatedCredentials() {
        DataSourceConfigService dataSources = new DataSourceConfigService(temp.resolve("host").toString()) {
            @Override public DataSourceConfig load() {
                return new DataSourceConfig(DbType.POSTGRESQL, "jdbc:postgresql://db:5432/fengyu",
                    "org.postgresql.Driver", "org.hibernate.dialect.PostgreSQLDialect",
                    "fengyu", "secret", null, "fengyu_admin", "admin-secret");
            }
        };
        PluginDbProvisioningStore store = new PluginDbProvisioningStore(temp.resolve("host"));
        store.put(new PluginDbProvisioningStore.ProvisionedPluginDb(
            "fan.summer.email", DbType.POSTGRESQL, "fengyu_fan_summer_email",
            "fengyu_plugin_email", "plugin-pw",
            "jdbc:postgresql://db:5432/fengyu?currentSchema=fengyu_fan_summer_email",
            "org.postgresql.Driver", "2026-08-08T00:00:00Z"));
        PluginRuntimeEnvironmentService service =
            new PluginRuntimeEnvironmentService(dataSources, temp.resolve("plugin-data").toString(), store);

        Map<String, String> env = service.environmentFor(manifest("fan.summer.email", List.of("database")));

        assertEquals("jdbc:postgresql://db:5432/fengyu?currentSchema=fengyu_fan_summer_email",
            env.get(PluginWorkerProtocol.DB_URL_ENV),
            "provisioned worker must receive its OWN url, not the host's");
        assertEquals("fengyu_plugin_email", env.get(PluginWorkerProtocol.DB_USERNAME_ENV));
        assertEquals("plugin-pw", env.get(PluginWorkerProtocol.DB_PASSWORD_ENV));
        assertFalse(env.get(PluginWorkerProtocol.DB_PASSWORD_ENV).contains("secret"),
            "host's global DB password must NEVER appear in the worker env");
    }

    @Test
    void notProvisionedRemoteDatabaseWorkerReceivesNoDatabaseEnv() {
        DataSourceConfigService dataSources = new DataSourceConfigService(temp.resolve("host").toString()) {
            @Override public DataSourceConfig load() {
                return new DataSourceConfig(DbType.MYSQL, "jdbc:mysql://db:3306/fengyu",
                    "com.mysql.cj.jdbc.Driver", "org.hibernate.dialect.MySQLDialect",
                    "fengyu", "secret", null, "fengyu_admin", "admin-secret");
            }
        };
        PluginDbProvisioningStore store = new PluginDbProvisioningStore(temp.resolve("host"));
        PluginRuntimeEnvironmentService service =
            new PluginRuntimeEnvironmentService(dataSources, temp.resolve("plugin-data").toString(), store);

        Map<String, String> env = service.environmentFor(manifest("fan.summer.email", List.of("database")));
        assertFalse(env.containsKey(PluginWorkerProtocol.DB_URL_ENV));
        assertFalse(env.containsKey(PluginWorkerProtocol.DB_USERNAME_ENV));
        assertFalse(env.containsKey(PluginWorkerProtocol.DB_PASSWORD_ENV));
        assertEquals("INFO", env.get(PluginWorkerProtocol.LOG_LEVEL_ENV));
        assertTrue(env.containsKey(PluginWorkerProtocol.PLUGIN_DATA_DIR_ENV));
    }

    @Test
    void incompleteProvisioningStatesNeverExposeCredentialsToWorker() {
        DataSourceConfigService dataSources = new DataSourceConfigService(temp.resolve("host-incomplete").toString()) {
            @Override public DataSourceConfig load() {
                return new DataSourceConfig(DbType.POSTGRESQL, "jdbc:postgresql://db/fengyu",
                    "org.postgresql.Driver", "org.hibernate.dialect.PostgreSQLDialect",
                    "host", "host-secret", null, "admin", "admin-secret");
            }
        };
        for (String state : List.of(PluginDbProvisioningStore.STATUS_PROVISIONING,
                PluginDbProvisioningStore.STATUS_DELETE_PENDING)) {
            Path stateRoot = temp.resolve("state-" + state);
            PluginDbProvisioningStore store = new PluginDbProvisioningStore(stateRoot);
            store.put(new PluginDbProvisioningStore.ProvisionedPluginDb(
                "fan.summer.email", DbType.POSTGRESQL, "plugin_schema", "plugin_user",
                "plugin-secret", "jdbc:postgresql://db/fengyu?currentSchema=plugin_schema",
                "org.postgresql.Driver", "t", state));
            PluginRuntimeEnvironmentService service = new PluginRuntimeEnvironmentService(
                dataSources, stateRoot.resolve("plugin-data").toString(), store);

            Map<String, String> env = service.environmentFor(
                manifest("fan.summer.email", List.of("database")));
            assertFalse(env.containsKey(PluginWorkerProtocol.DB_URL_ENV), state);
            assertFalse(env.containsKey(PluginWorkerProtocol.DB_PASSWORD_ENV), state);
        }
    }

    @Test
    void provisionedH2WorkerReceivesTcpUrlAndItsOwnCredentials() {
        DataSourceConfigService dataSources = new DataSourceConfigService(temp.resolve("host").toString()) {
            @Override public DataSourceConfig load() {
                return new DataSourceConfig(DbType.H2, "jdbc:h2:tcp://127.0.0.1:12345/fengyu",
                    "org.h2.Driver", "org.hibernate.dialect.H2Dialect", "sa", "", null, "sa", "");
            }
        };
        PluginDbProvisioningStore store = new PluginDbProvisioningStore(temp.resolve("host"));
        store.put(new PluginDbProvisioningStore.ProvisionedPluginDb(
            "fan.summer.email", DbType.H2, "fengyu_fan_summer_email", "fengyu_plugin_email",
            "plugin-pw", "jdbc:h2:tcp://127.0.0.1:12345/fengyu;SCHEMA=fengyu_fan_summer_email",
            "org.h2.Driver", "2026-08-08T00:00:00Z"));
        PluginRuntimeEnvironmentService service =
            new PluginRuntimeEnvironmentService(dataSources, temp.resolve("plugin-data").toString(), store);

        Map<String, String> env = service.environmentFor(manifest("fan.summer.email", List.of("database")));
        assertEquals("h2", env.get(PluginWorkerProtocol.DB_TYPE_ENV));
        assertEquals("jdbc:h2:tcp://127.0.0.1:12345/fengyu;SCHEMA=fengyu_fan_summer_email",
            env.get(PluginWorkerProtocol.DB_URL_ENV));
        assertEquals("fengyu_plugin_email", env.get(PluginWorkerProtocol.DB_USERNAME_ENV));
        assertEquals("plugin-pw", env.get(PluginWorkerProtocol.DB_PASSWORD_ENV));
    }

    @Test
    void pluginWithoutPermissionReceivesNoDatabaseSecrets() {
        DataSourceConfigService dataSources = new DataSourceConfigService(temp.resolve("host").toString());
        dataSources.save(new DataSourceConfig(DbType.H2, "jdbc:h2:mem:host", "org.h2.Driver",
            "org.hibernate.dialect.H2Dialect", "sa", "secret", null));
        PluginRuntimeEnvironmentService service =
            new PluginRuntimeEnvironmentService(dataSources, temp.resolve("plugin-data").toString());

        Map<String, String> environment =
            service.environmentFor(manifest("fan.summer.markdown", List.of()));
        assertEquals("INFO", environment.get(PluginWorkerProtocol.LOG_LEVEL_ENV));
        assertFalse(environment.containsKey(PluginWorkerProtocol.DB_PASSWORD_ENV));
        assertTrue(Files.isDirectory(Path.of(
            environment.get(PluginWorkerProtocol.PLUGIN_DATA_DIR_ENV))));
    }

    @Test
    void sqliteTypeUsesExactContractValueUnderTurkishLocale() {
        DataSourceConfig config = new DataSourceConfig(DbType.SQLITE, "jdbc:sqlite:mail.db",
            "org.sqlite.JDBC", "org.hibernate.community.dialect.SQLiteDialect", "", "", null);
        DataSourceConfigService dataSources = new DataSourceConfigService(temp.resolve("host").toString()) {
            @Override public DataSourceConfig load() {
                return config;
            }
        };
        PluginRuntimeEnvironmentService service =
            new PluginRuntimeEnvironmentService(dataSources, temp.resolve("plugin-data").toString());
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("sqlite", service.environmentFor(
                manifest("fan.summer.email", List.of("database"))).get(PluginWorkerProtocol.DB_TYPE_ENV));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void rejectsTraversalAndAbsolutePluginIdsWithoutCreatingOutsideDirectories() {
        DataSourceConfig config = new DataSourceConfig(DbType.H2, "jdbc:h2:mem:host", "org.h2.Driver",
            "org.hibernate.dialect.H2Dialect", "sa", "secret", null);
        DataSourceConfigService dataSources = new DataSourceConfigService(temp.resolve("host").toString()) {
            @Override public DataSourceConfig load() {
                return config;
            }
        };
        Path dataRoot = temp.resolve("plugin-data");
        PluginRuntimeEnvironmentService service =
            new PluginRuntimeEnvironmentService(dataSources, dataRoot.toString());
        Path traversalTarget = temp.resolve("traversal-outside");
        Path absoluteTarget = temp.resolve("absolute-outside").toAbsolutePath();

        assertThrows(IllegalArgumentException.class, () -> service.environmentFor(
            manifest("../" + traversalTarget.getFileName(), List.of("database"))));
        assertThrows(IllegalArgumentException.class, () -> service.environmentFor(
            manifest(absoluteTarget.toString(), List.of("database"))));
        assertFalse(Files.exists(traversalTarget));
        assertFalse(Files.exists(absoluteTarget));
    }

    private static PluginManifest manifest(String id, List<String> permissions) {
        return new PluginManifest(2, id, "Test", "Test", "1.0.0", "FengYu", "email", "net",
            new PluginManifest.Ui("ui/index.html"),
            new PluginManifest.Backend(60L),
            permissions, null, true, null, List.of());
    }
}
