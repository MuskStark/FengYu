package fan.summer.fengyu.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-tests {@link SetupController}'s reset path ({@code DELETE /api/setup/config}) using a
 * no-op, recording {@code exitAction} so {@code System.exit} never fires during tests. The
 * {@code initialize} exit path shares the same seam but is not exercised here.
 */
class SetupControllerTest {

    @TempDir
    Path tempDir;

    private DataSourceConfigService newService() {
        return new DataSourceConfigService(tempDir.toString());
    }

    private SetupController newController(DataSourceConfigService svc, AtomicBoolean exitFired) {
        return new SetupController(svc, () -> exitFired.set(true));
    }

    @Test
    void clearConfig_backsUpConfigAndSignalsRestart() {
        DataSourceConfigService svc = newService();
        // Seed a config.
        WizardParams params = new WizardParams(
                tempDir.resolve("database/fengyu").toString(), null, null, null, null, null);
        svc.save(svc.buildFromWizard(DbType.H2, params));
        assertTrue(Files.exists(svc.configFileForTest()), "precondition: config exists");

        AtomicBoolean exitFired = new AtomicBoolean(false);
        SetupController controller = newController(svc, exitFired);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) controller.clearConfig();

        assertEquals(true, result.get("success"));
        assertEquals("restart", result.get("action"));
        assertTrue(exitFired.get(), "exit action should have been invoked");
        assertFalse(Files.exists(svc.configFileForTest()), "config should be backed up / gone");
        assertTrue(Files.exists(tempDir.resolve("config/datasource.properties.bak")),
                "backup should exist");
    }

    @Test
    void clearConfig_whenNoConfig_isIdempotent() {
        DataSourceConfigService svc = newService();
        AtomicBoolean exitFired = new AtomicBoolean(false);
        SetupController controller = newController(svc, exitFired);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) controller.clearConfig();

        assertEquals(true, result.get("success"), "idempotent: no-op still success");
        assertEquals("restart", result.get("action"));
        assertTrue(exitFired.get(), "restart still signalled even with no config");
    }

    @Test
    void types_embedded_filePathCarriesResolvedDefault() {
        DataSourceConfigService svc = newService();
        SetupController controller = newController(svc, new java.util.concurrent.atomic.AtomicBoolean(false));

        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> types = (java.util.List<Map<String, Object>>) controller.types();

        Map<String, Object> h2 = types.stream()
                .filter(t -> "h2".equals(t.get("type")))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> fields = (java.util.List<Map<String, Object>>) h2.get("fields");
        Map<String, Object> filePath = fields.stream()
                .filter(f -> "filePath".equals(f.get("name")))
                .findFirst().orElseThrow();

        assertEquals(svc.defaultEmbeddedPath().toString(),
                filePath.get("default"),
                "embedded filePath must advertise the resolved program default");
    }

    @Test
    void types_remoteFieldsDoNotCarryFilePathDefault() {
        DataSourceConfigService svc = newService();
        SetupController controller = newController(svc, new java.util.concurrent.atomic.AtomicBoolean(false));

        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> types = (java.util.List<Map<String, Object>>) controller.types();

        Map<String, Object> mysql = types.stream()
                .filter(t -> "mysql".equals(t.get("type")))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> fields = (java.util.List<Map<String, Object>>) mysql.get("fields");
        boolean anyFilePath = fields.stream().anyMatch(f -> "filePath".equals(f.get("name")));
        assertFalse(anyFilePath, "remote types must not expose a filePath field");
    }
}
