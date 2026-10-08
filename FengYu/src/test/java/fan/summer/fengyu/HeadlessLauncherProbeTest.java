package fan.summer.fengyu;

import fan.summer.fengyu.config.H2TcpServerConfig;
import fan.summer.fengyu.runtime.RuntimePaths;
import fan.summer.fengyu.setup.DataSourceConfig;
import fan.summer.fengyu.setup.DataSourceConfigService;
import fan.summer.fengyu.setup.DbType;
import fan.summer.fengyu.setup.WizardParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.server.PortInUseException;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.BindException;
import java.nio.charset.StandardCharsets;
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

    // ---- CLI parsing: warnings must never echo option values --------------------------

    @Test
    void unknownOptionLabelCarriesOnlyTheFlagNameNeverTheValue() {
        // A mistyped `--Token=<secret>` must surface the flag NAME only — echoing the raw
        // argument would print the launch token to stderr, which launch scripts may redirect
        // into a log file.
        assertEquals("--Token", HeadlessLauncher.unknownOptionLabel("--Token=sekrit-launch-token"));
        assertEquals("--ports", HeadlessLauncher.unknownOptionLabel("--ports"));
        assertFalse(HeadlessLauncher.unknownOptionLabel("--token=hunter2").contains("hunter2"),
                "the label must never carry the option value");
    }

    @Test
    void parseArgsWarnsAboutAMistypedFlagWithoutEchoingItsValue() {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            HeadlessLauncher.LaunchArgs parsed = HeadlessLauncher.parseArgs(
                    new String[] {"--Token=sekrit-launch-token", "--port=25000"}, "");
            assertEquals("25000", parsed.port(), "the valid flag still applies");
            assertEquals("", parsed.token(), "a mistyped token flag leaves the token unset");
            assertFalse(parsed.explicitEmptyToken());
            String stderr = captured.toString(StandardCharsets.UTF_8);
            assertTrue(stderr.contains("--Token"), "the warning must name the mistyped flag: " + stderr);
            assertFalse(stderr.contains("sekrit-launch-token"),
                    "the warning must never echo the option value: " + stderr);
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    void emptyTokenFlagWarnsLoudlyInsteadOfSilentlyDisablingAuth() {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            HeadlessLauncher.LaunchArgs parsed = HeadlessLauncher.parseArgs(
                    new String[] {"--token="}, "");
            assertTrue(parsed.explicitEmptyToken(), "an explicit empty --token= must be flagged");
            assertEquals("", parsed.token());
            String stderr = captured.toString(StandardCharsets.UTF_8);
            assertTrue(stderr.contains("UNPROTECTED"),
                    "the warning must state the auth-disabled consequence loudly: " + stderr);
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    void absentTokenFlagStaysQuietAndKeepsTheEnvironmentDefault() {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            HeadlessLauncher.LaunchArgs parsed = HeadlessLauncher.parseArgs(
                    new String[] {"--port=24056"}, "env-token");
            assertEquals("env-token", parsed.token(), "the environment default survives");
            assertFalse(parsed.explicitEmptyToken());
            assertEquals("", captured.toString(StandardCharsets.UTF_8).trim(),
                    "no warning without a mistyped or empty flag");
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    void unreachableH2_fallsBackToSetup_andStopsThePreProbeServer() throws Exception {
        // main() starts the H2 TCP server BEFORE the reachability probe; when the probe then
        // fails, the fallback must tear that server back down (SETUP mode boots
        // SetupApplication, which owns no lifecycle for it — a stale loopback listener would
        // otherwise outlive the decision).
        DataSourceConfigService svc = newService();
        WizardParams params = new WizardParams(
                tempDir.resolve("database/fengyu").toString(), null, null, null, null, null);
        svc.save(svc.buildFromWizard(DbType.H2, params));
        int started = H2TcpServerConfig.startIfNeeded(svc);
        org.junit.jupiter.api.Assumptions.assumeTrue(started > 0,
                "the H2 TCP server must start for this wiring test");
        // Remove the database so the probe cannot reach it (mirrors HeadlessLauncherProbe
        // flow: the probe's tcp:// connection is refused without -ifNotExists).
        deleteRecursively(tempDir.resolve("database"));

        assertFalse(HeadlessLauncher.probeAndDecide(svc), "unreachable DB → SETUP mode");
        assertEquals(0, H2TcpServerConfig.port(),
                "the pre-probe H2 TCP server must be stopped before the SETUP fallback");
    }

    @Test
    void primeRuntimeDirectoriesPointsTheLoggerAtTheCreatedLogDir() throws Exception {
        Path root = tempDir.resolve("writable-root");
        String previousRoot = System.getProperty(RuntimePaths.ROOT_PROPERTY);
        String previousLogDir = System.getProperty("fengyu.log.dir");
        try {
            HeadlessLauncher.primeRuntimeDirectories(root.toAbsolutePath().normalize());
            Path logDir = Path.of(System.getProperty("fengyu.log.dir"));
            assertEquals(root.resolve("logs"), logDir);
            assertTrue(Files.isDirectory(logDir), "the log directory must actually exist");
        } finally {
            restoreProperty(RuntimePaths.ROOT_PROPERTY, previousRoot);
            restoreProperty("fengyu.log.dir", previousLogDir);
        }
    }

    @Test
    void unwritableLogDirectoryDegradesToATempDirInsteadOfANonexistentPath() throws Exception {
        // `logs` exists as a regular FILE → createDirectories fails → the old code still pointed
        // log.dir at the (nonexistent) directory; the fix must fall back to an existing temp dir.
        Path root = tempDir.resolve("blocked-root");
        Files.createDirectories(root);
        Files.writeString(root.resolve("logs"), "not a directory");
        String previousRoot = System.getProperty(RuntimePaths.ROOT_PROPERTY);
        String previousLogDir = System.getProperty("fengyu.log.dir");
        try {
            HeadlessLauncher.primeRuntimeDirectories(root.toAbsolutePath().normalize());
            Path logDir = Path.of(System.getProperty("fengyu.log.dir"));
            assertEquals(
                    Path.of(System.getProperty("java.io.tmpdir"), "fengyu-logs"), logDir);
            assertTrue(Files.isDirectory(logDir), "the fallback directory must actually exist");
        } finally {
            restoreProperty(RuntimePaths.ROOT_PROPERTY, previousRoot);
            restoreProperty("fengyu.log.dir", previousLogDir);
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
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
