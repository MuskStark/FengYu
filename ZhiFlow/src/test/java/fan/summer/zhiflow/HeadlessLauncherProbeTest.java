package fan.summer.zhiflow;

import fan.summer.zhiflow.setup.DataSourceConfig;
import fan.summer.zhiflow.setup.DataSourceConfigService;
import fan.summer.zhiflow.setup.DbType;
import fan.summer.zhiflow.setup.WizardParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-tests {@link HeadlessLauncher#probeAndDecide(DataSourceConfigService)} — the startup
 * decision: probe the configured DB; on failure back up the config and fall back to SETUP mode.
 */
class HeadlessLauncherProbeTest {

    @TempDir
    Path tempDir;

    private DataSourceConfigService newService() {
        return new DataSourceConfigService(tempDir.toString());
    }

    @Test
    void noConfig_returnsFalse_noBackup() {
        boolean configured = HeadlessLauncher.probeAndDecide(newService());
        assertFalse(configured, "no config file → SETUP mode");
        assertFalse(Files.exists(tempDir.resolve("config/datasource.properties.bak")),
                "nothing to back up");
    }

    @Test
    void unreachableSqlite_backsUpAndReturnsFalse() {
        DataSourceConfigService svc = newService();
        // Save a config pointing at a SQLite file whose PARENT DIRECTORY does not exist.
        // buildFromWizard creates the parent dir, so first save a normal config, then delete
        // the database dir to simulate the "database removed" scenario.
        WizardParams params = new WizardParams(
                tempDir.resolve("database/zhiflow").toString(), null, null, null, null, null);
        DataSourceConfig cfg = svc.buildFromWizard(DbType.SQLITE, params);
        svc.save(cfg);
        // Remove the database directory to simulate the user deleting it.
        deleteRecursively(tempDir.resolve("database"));

        boolean configured = HeadlessLauncher.probeAndDecide(svc);

        assertFalse(configured, "unreachable DB → SETUP mode");
        assertFalse(Files.exists(svc.configFileForTest()),
                "stale config should have been backed up (removed)");
        assertTrue(Files.exists(tempDir.resolve("config/datasource.properties.bak")),
                "backup .bak should exist");
    }

    @Test
    void reachableH2_returnsTrue_configUntouched() {
        DataSourceConfigService svc = newService();
        // H2 creates the file on connect; point at a temp file path whose parent exists.
        WizardParams params = new WizardParams(
                tempDir.resolve("database/zhiflow").toString(), null, null, null, null, null);
        DataSourceConfig cfg = svc.buildFromWizard(DbType.H2, params);
        svc.save(cfg);

        boolean configured = HeadlessLauncher.probeAndDecide(svc);

        assertTrue(configured, "reachable H2 → APP mode");
        assertTrue(Files.exists(svc.configFileForTest()),
                "reachable config must NOT be backed up / deleted");
    }

    private static void deleteRecursively(Path p) {
        if (!Files.exists(p)) return;
        try (var stream = Files.walk(p)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try { Files.delete(path); } catch (Exception ignored) {}
                    });
        } catch (Exception ignored) {}
    }
}
