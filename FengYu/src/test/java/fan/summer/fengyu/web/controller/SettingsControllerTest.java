package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.service.AiConfigServiceHeadless;
import fan.summer.fengyu.log.LoggingLevelService;
import fan.summer.fengyu.plugin.runtime.PluginProcessManager;
import fan.summer.fengyu.setup.DataSourceConfigService;
import fan.summer.fengyu.setup.DbType;
import fan.summer.fengyu.setup.WizardParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-tests {@link SettingsController#resetDatabase()} using a no-op, recording exit action and
 * a mock {@link AiConfigServiceHeadless} (its methods are not exercised by the reset path).
 */
class SettingsControllerTest {

    @TempDir
    Path tempDir;

    private DataSourceConfigService newService() {
        return new DataSourceConfigService(tempDir.toString());
    }

    @Test
    void resetDatabase_backsUpConfigAndSignalsRestart() {
        DataSourceConfigService svc = newService();
        WizardParams params = new WizardParams(
                tempDir.resolve("database/fengyu").toString(), null, null, null, null, null);
        svc.save(svc.buildFromWizard(DbType.H2, params));
        assertTrue(Files.exists(svc.configFileForTest()), "precondition: config exists");

        AtomicBoolean exitFired = new AtomicBoolean(false);
        SettingsController controller = new SettingsController(
                mock(AiConfigServiceHeadless.class), svc, () -> exitFired.set(true));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) controller.resetDatabase();

        assertEquals(true, result.get("success"));
        assertEquals("restart", result.get("action"));
        assertTrue(exitFired.get(), "exit action should have been invoked");
        assertFalse(Files.exists(svc.configFileForTest()), "config should be backed up / gone");
        assertTrue(Files.exists(tempDir.resolve("config/datasource.properties.bak")),
                "backup should exist");
    }

    @Test
    void resetDatabase_whenNoConfig_isIdempotent() {
        DataSourceConfigService svc = newService();
        AtomicBoolean exitFired = new AtomicBoolean(false);
        SettingsController controller = new SettingsController(
                mock(AiConfigServiceHeadless.class), svc, () -> exitFired.set(true));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) controller.resetDatabase();

        assertEquals(true, result.get("success"));
        assertTrue(exitFired.get(), "restart still signalled");
    }

    @Test
    void putAppliesSameLogLevelToHostAndRunningPlugins() {
        AiConfigServiceHeadless config = mock(AiConfigServiceHeadless.class);
        LoggingLevelService logging = mock(LoggingLevelService.class);
        PluginProcessManager pluginProcesses = mock(PluginProcessManager.class);
        when(logging.setLevel("debug")).thenReturn("DEBUG");
        when(logging.currentLevel()).thenReturn("DEBUG");
        SettingsController controller = new SettingsController(
            config, newService(), logging, pluginProcesses, () -> {});

        Map<String, Object> result;
        try (var ignored = mockStatic(AiConfigServiceHeadless.class)) {
            result = controller.put(Map.of("logLevel", "debug"));
        }

        assertEquals("DEBUG", result.get("logLevel"));
        verify(logging).setLevel("debug");
        verify(pluginProcesses).updateLogLevel("DEBUG");
    }

    @Test
    void putRejectsEnablingUnsandboxedPluginsOnSandboxedPlatform() {
        // On the CI host (Linux/macOS) a native sandbox is available, so enabling must be rejected.
        // This documents + guards the platform gate; IllegalArgumentException -> HTTP 400 via
        // GlobalExceptionHandler. (The NONE-platform accept path is covered by manual verification.)
        AiConfigServiceHeadless config = mock(AiConfigServiceHeadless.class);
        SettingsController controller = new SettingsController(
            config, newService(), mock(LoggingLevelService.class), mock(PluginProcessManager.class), () -> {});

        // The unsandboxed read/write helpers are static facades over the AiConfigServiceHeadless
        // singleton (Task 1); mockStatic intercepts them just as the logLevel test above does.
        try (var mockedStatic = mockStatic(AiConfigServiceHeadless.class)) {
            // Enabling on a sandboxed platform throws.
            assertThrows(IllegalArgumentException.class,
                () -> controller.put(Map.of("unsandboxedPlugins", true)));

            // Disabling is always allowed (closing a protection boundary is safe everywhere) and
            // must NOT throw, even on a sandboxed platform.
            controller.put(Map.of("unsandboxedPlugins", false));
            mockedStatic.verify(() -> AiConfigServiceHeadless.setUnsandboxedPluginsEnabled(false));
        }
    }
}
