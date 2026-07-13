package fan.summer.fengyu.plugin.runtime;

import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.sdk.PluginEnvironment;
import fan.summer.fengyu.setup.DataSourceConfig;
import fan.summer.fengyu.setup.DataSourceConfigService;
import fan.summer.fengyu.setup.DbType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginRuntimeEnvironmentServiceTest {
    @TempDir Path temp;

    @Test
    void databasePermissionReceivesConnectionAndStableDataDirectory() {
        DataSourceConfigService dataSources = new DataSourceConfigService(temp.resolve("host").toString());
        dataSources.save(new DataSourceConfig(DbType.H2, "jdbc:h2:mem:host", "org.h2.Driver",
            "org.hibernate.dialect.H2Dialect", "sa", "secret", null));
        PluginRuntimeEnvironmentService service =
            new PluginRuntimeEnvironmentService(dataSources, temp.resolve("plugin-data").toString());

        Map<String, String> first = service.environmentFor(manifest("fan.summer.email", List.of("database")));
        Map<String, String> second = service.environmentFor(manifest("fan.summer.email", List.of("database")));

        assertEquals("h2", first.get(PluginEnvironment.DB_TYPE));
        assertEquals("jdbc:h2:mem:host", first.get(PluginEnvironment.DB_URL));
        assertEquals("secret", first.get(PluginEnvironment.DB_PASSWORD));
        assertEquals(first, second);
        assertTrue(Files.isDirectory(Path.of(first.get(PluginEnvironment.PLUGIN_DATA_DIR))));
    }

    @Test
    void pluginWithoutPermissionReceivesNoDatabaseSecrets() {
        DataSourceConfigService dataSources = new DataSourceConfigService(temp.resolve("host").toString());
        dataSources.save(new DataSourceConfig(DbType.H2, "jdbc:h2:mem:host", "org.h2.Driver",
            "org.hibernate.dialect.H2Dialect", "sa", "secret", null));
        PluginRuntimeEnvironmentService service =
            new PluginRuntimeEnvironmentService(dataSources, temp.resolve("plugin-data").toString());

        assertTrue(service.environmentFor(manifest("fan.summer.markdown", List.of())).isEmpty());
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
                manifest("fan.summer.email", List.of("database"))).get(PluginEnvironment.DB_TYPE));
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
        return new PluginManifest(1, id, "Test", "Test", "1.0.0", "FengYu", "email", "net",
            new PluginManifest.Ui("ui/index.html"),
            new PluginManifest.Backend("java -jar backend/worker.jar", "json-rpc-2.0"),
            permissions, null, true, List.of());
    }
}
