package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.service.AiConfigServiceHeadless;
import fan.summer.fengyu.ai.tools.ComputerTool;
import fan.summer.fengyu.log.LoggingLevelService;
import fan.summer.fengyu.plugin.runtime.PluginProcessManager;
import fan.summer.fengyu.security.ProcessSandbox;
import fan.summer.fengyu.setup.DataSourceConfigService;
import fan.summer.fengyu.setup.DbType;
import fan.summer.fengyu.setup.WizardParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
        // The platform gate keys on ProcessSandbox.isNativeSandboxAvailable(), which is true only on
        // FULL-isolation platforms (Linux bwrap). macOS is now honestly reported as REDUCED (not
        // full), so it no longer counts as "sandboxed" for this gate. Pin a full-sandbox platform
        // via mockStatic so the "enabling is rejected" contract is deterministic on every CI host.
        AiConfigServiceHeadless config = mock(AiConfigServiceHeadless.class);
        SettingsController controller = new SettingsController(
            config, newService(), mock(LoggingLevelService.class), mock(PluginProcessManager.class), () -> {});

        // The unsandboxed read/write helpers are static facades over the AiConfigServiceHeadless
        // singleton (Task 1); mockStatic intercepts them just as the logLevel test above does.
        try (var mockedAi = mockStatic(AiConfigServiceHeadless.class);
             var mockedSandbox = mockStatic(ProcessSandbox.class)) {
            mockedSandbox.when(ProcessSandbox::isNativeSandboxAvailable).thenReturn(true);
            // Enabling on a sandboxed platform throws.
            assertThrows(IllegalArgumentException.class,
                () -> controller.put(Map.of("unsandboxedPlugins", true)));

            // Disabling is always allowed (closing a protection boundary is safe everywhere) and
            // must NOT throw, even on a sandboxed platform.
            controller.put(Map.of("unsandboxedPlugins", false));
            mockedAi.verify(() -> AiConfigServiceHeadless.setUnsandboxedPluginsEnabled(false));
        }
    }

    @Test
    void putUpdateApiBase_acceptsAbsoluteHttpUrlAndClearsWhenEmpty() {
        AiConfigServiceHeadless config = mock(AiConfigServiceHeadless.class);
        SettingsController controller = new SettingsController(
            config, newService(), mock(LoggingLevelService.class), mock(PluginProcessManager.class), () -> {});

        // setUpdateApiBase is a static facade, so mockStatic intercepts it (the mock instance is only
        // the receiver — the call goes to the static method). A valid URL is persisted with the
        // trailing slash normalized away; an empty value clears the override.
        try (var mockedAi = mockStatic(AiConfigServiceHeadless.class)) {
            controller.put(Map.of("updateApiBase", "http://10.0.0.5:8088/"));
            mockedAi.verify(() -> AiConfigServiceHeadless.setUpdateApiBase("http://10.0.0.5:8088"));

            controller.put(Map.of("updateApiBase", ""));
            mockedAi.verify(() -> AiConfigServiceHeadless.setUpdateApiBase(""));
        }
    }

    @Test
    void putUpdateApiBase_rejectsInvalidValues() {
        AiConfigServiceHeadless config = mock(AiConfigServiceHeadless.class);
        SettingsController controller = new SettingsController(
            config, newService(), mock(LoggingLevelService.class), mock(PluginProcessManager.class), () -> {});

        try (var ignored = mockStatic(AiConfigServiceHeadless.class)) {
            // Non-http scheme.
            assertThrows(IllegalArgumentException.class,
                () -> controller.put(Map.of("updateApiBase", "ftp://10.0.0.5:8088")));
            // Relative path, not absolute.
            assertThrows(IllegalArgumentException.class,
                () -> controller.put(Map.of("updateApiBase", "10.0.0.5:8088")));
            // Query parameter present.
            assertThrows(IllegalArgumentException.class,
                () -> controller.put(Map.of("updateApiBase", "http://10.0.0.5:8088?x=1")));
            // Embedded credentials.
            assertThrows(IllegalArgumentException.class,
                () -> controller.put(Map.of("updateApiBase", "http://u:p@10.0.0.5:8088")));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void computerUseSwitchReportsCapabilityAndPersists() {
        AiConfigServiceHeadless config = mock(AiConfigServiceHeadless.class);
        LoggingLevelService logging = mock(LoggingLevelService.class);
        ObjectProvider<ComputerTool> provider = mock(ObjectProvider.class);
        ComputerTool tool = mock(ComputerTool.class);
        when(provider.getIfAvailable()).thenReturn(tool);
        Map<String, Object> availability = new LinkedHashMap<>();
        availability.put("available", true);
        availability.put("reason", null);
        when(tool.availability()).thenReturn(availability);
        SettingsController controller = new SettingsController(
            config, newService(), logging, mock(PluginProcessManager.class), provider, () -> {});

        try (var mockedAi = mockStatic(AiConfigServiceHeadless.class)) {
            mockedAi.when(AiConfigServiceHeadless::isComputerUseEnabled).thenReturn(true);

            Map<String, Object> get = controller.get();
            assertEquals(true, get.get("computerUseEnabled"));
            assertEquals(availability, (Map<String, Object>) get.get("computerUse"));

            // Toggling off persists and is reflected in the response of the same PUT.
            mockedAi.when(AiConfigServiceHeadless::isComputerUseEnabled).thenReturn(false);
            Map<String, Object> put = controller.put(Map.of("computerUseEnabled", false));
            mockedAi.verify(() -> AiConfigServiceHeadless.setComputerUseEnabled(false));
            assertEquals(false, put.get("computerUseEnabled"));
        }
    }

    @Test
    void computerUseCapabilityIsNullWhenBeanAbsent() {
        AiConfigServiceHeadless config = mock(AiConfigServiceHeadless.class);
        SettingsController controller = new SettingsController(
            config, newService(), mock(LoggingLevelService.class), mock(PluginProcessManager.class), () -> {});

        try (var mockedAi = mockStatic(AiConfigServiceHeadless.class)) {
            mockedAi.when(AiConfigServiceHeadless::isComputerUseEnabled).thenReturn(true);
            Map<String, Object> get = controller.get();
            assertNull(get.get("computerUse"), "web mode (no ComputerTool bean) hides the card");
            assertEquals(true, get.get("computerUseEnabled"));
        }
    }
}
