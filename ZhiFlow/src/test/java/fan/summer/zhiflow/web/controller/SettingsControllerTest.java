package fan.summer.zhiflow.web.controller;

import fan.summer.zhiflow.ai.service.AiConfigServiceHeadless;
import fan.summer.zhiflow.setup.DataSourceConfigService;
import fan.summer.zhiflow.setup.DbType;
import fan.summer.zhiflow.setup.WizardParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

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
                tempDir.resolve("database/zhiflow").toString(), null, null, null, null, null);
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
}
