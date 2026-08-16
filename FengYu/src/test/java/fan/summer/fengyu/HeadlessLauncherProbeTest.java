package fan.summer.fengyu;

import fan.summer.fengyu.setup.DataSourceConfig;
import fan.summer.fengyu.setup.DataSourceConfigService;
import fan.summer.fengyu.setup.DbType;
import fan.summer.fengyu.setup.WizardParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.server.PortInUseException;

import java.net.BindException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-tests {@link HeadlessLauncher#probeAndDecide(DataSourceConfigService)} — the startup
 * decision: probe the configured DB; on failure back up the config and fall back to SETUP mode.
 */
class HeadlessLauncherProbeTest {

    @TempDir
    Path tempDir;

    @Test
    void packagedRuntimeDefaultsKeepPluginUploadsReachable() {
        Map<String, Object> defaults = HeadlessLauncher.runtimeDefaults();

        assertEquals("127.0.0.1", defaults.get("server.address"));
        assertEquals("128MB", defaults.get("spring.servlet.multipart.max-file-size"));
        assertEquals("128MB", defaults.get("spring.servlet.multipart.max-request-size"));
    }

    @Test
    void desktopBackendBecomesMacUiElementWithoutChangingOtherPlatforms() {
        String previous = System.getProperty(HeadlessLauncher.MAC_UI_ELEMENT_PROPERTY);
        try {
            System.clearProperty(HeadlessLauncher.MAC_UI_ELEMENT_PROPERTY);
            HeadlessLauncher.configureDesktopPlatform(true, "Mac OS X");
            assertEquals("true", System.getProperty(HeadlessLauncher.MAC_UI_ELEMENT_PROPERTY));

            System.clearProperty(HeadlessLauncher.MAC_UI_ELEMENT_PROPERTY);
            HeadlessLauncher.configureDesktopPlatform(true, "Windows 11");
            assertNull(System.getProperty(HeadlessLauncher.MAC_UI_ELEMENT_PROPERTY));
            HeadlessLauncher.configureDesktopPlatform(true, "Linux");
            assertNull(System.getProperty(HeadlessLauncher.MAC_UI_ELEMENT_PROPERTY));
        } finally {
            if (previous == null) {
                System.clearProperty(HeadlessLauncher.MAC_UI_ELEMENT_PROPERTY);
            } else {
                System.setProperty(HeadlessLauncher.MAC_UI_ELEMENT_PROPERTY, previous);
            }
        }
    }

    @Test
    void desktopPlatformConfigurationRespectsExplicitMacOverride() {
        String previous = System.getProperty(HeadlessLauncher.MAC_UI_ELEMENT_PROPERTY);
        try {
            System.setProperty(HeadlessLauncher.MAC_UI_ELEMENT_PROPERTY, "false");
            HeadlessLauncher.configureDesktopPlatform(true, "Mac OS X");
            assertEquals("false", System.getProperty(HeadlessLauncher.MAC_UI_ELEMENT_PROPERTY));
        } finally {
            if (previous == null) {
                System.clearProperty(HeadlessLauncher.MAC_UI_ELEMENT_PROPERTY);
            } else {
                System.setProperty(HeadlessLauncher.MAC_UI_ELEMENT_PROPERTY, previous);
            }
        }
    }

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
                tempDir.resolve("database/fengyu").toString(), null, null, null, null, null);
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
                tempDir.resolve("database/fengyu").toString(), null, null, null, null, null);
        DataSourceConfig cfg = svc.buildFromWizard(DbType.H2, params);
        svc.save(cfg);

        boolean configured = HeadlessLauncher.probeAndDecide(svc);

        assertTrue(configured, "reachable H2 → APP mode");
        assertTrue(Files.exists(svc.configFileForTest()),
                "reachable config must NOT be backed up / deleted");
    }

    @Test
    void runtimeDefaultsKeepAllWritableStateUnderConfiguredRoot() {
        Path root = tempDir.resolve("runtime").toAbsolutePath().normalize();
        Map<String, Object> defaults = HeadlessLauncher.runtimeDefaults(root);

        assertEquals(root.resolve("plugins").toString(), defaults.get("fengyu.plugins.directory"));
        assertEquals(root.resolve("plugin-data").toString(), defaults.get("fengyu.plugins.data-directory"));
        assertEquals(root.resolve("skills").toString(), defaults.get("fengyu.skills.directory"));
        assertEquals(root.resolve("runtime-files").toString(), defaults.get("fengyu.runtime-files.directory"));
    }

    @Test
    void retriesOnlyActualPortBindingFailures() {
        assertTrue(HeadlessLauncher.isPortBindFailure(
                new IllegalStateException("startup failed", new PortInUseException(24056))));
        assertTrue(HeadlessLauncher.isPortBindFailure(
                new IllegalStateException("startup failed", new BindException("Address already in use"))));
        assertFalse(HeadlessLauncher.isPortBindFailure(
                new IllegalStateException("LoggerFactory is not a Logback LoggerContext")));
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
