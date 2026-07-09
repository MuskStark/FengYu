package fan.summer.zhiflow.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class DataSourceConfigServiceTest {

    @TempDir
    Path tempDir;

    private DataSourceConfigService newService() {
        return new DataSourceConfigService(tempDir.toString());
    }

    @Test
    void load_fileMissing_returnsNull() {
        assertNull(newService().load());
    }

    @Test
    void save_thenLoad_roundtripsH2Config() {
        DataSourceConfigService svc = newService();
        WizardParams params = new WizardParams(
                tempDir.resolve("database/zhiflow").toString(), null, null, null, null, null);
        DataSourceConfig cfg = svc.buildFromWizard(DbType.H2, params);
        svc.save(cfg);

        DataSourceConfig loaded = svc.load();
        assertNotNull(loaded);
        assertEquals(DbType.H2, loaded.type());
        assertEquals("org.h2.Driver", loaded.driver());
        assertTrue(loaded.url().startsWith("jdbc:h2:file:"));
        assertEquals("", loaded.username());   // embedded: no credentials
    }

    @Test
    void save_mysqlConfig_passwordIsEncrypted() throws Exception {
        DataSourceConfigService svc = newService();
        WizardParams params = new WizardParams(
                null, "db.example.com", 3306, "zhiflow", "admin", "s3cret");
        DataSourceConfig cfg = svc.buildFromWizard(DbType.MYSQL, params);
        svc.save(cfg);

        // Read the raw file — password must NOT be plaintext
        Properties props = svc.readRawForTest();
        String storedPw = props.getProperty("db.password");
        assertNotNull(storedPw);
        assertNotEquals("s3cret", storedPw);
        assertTrue(storedPw.startsWith("ENC("));

        // And load() decrypts it back
        DataSourceConfig loaded = svc.load();
        assertEquals("s3cret", loaded.password());
    }

    @Test
    void buildFromWizard_mysql_assemblesCorrectUrl() {
        WizardParams params = new WizardParams(null, "localhost", 3306, "zhiflow", "root", "pw");
        DataSourceConfig cfg = newService().buildFromWizard(DbType.MYSQL, params);
        assertEquals("jdbc:mysql://localhost:3306/zhiflow", cfg.url());
        assertEquals("com.mysql.cj.jdbc.Driver", cfg.driver());
        assertEquals("root", cfg.username());
    }

    @Test
    void buildFromWizard_embedded_resolvesRelativePathToAbsolute() {
        DataSourceConfigService svc = newService();
        WizardParams params = new WizardParams(".zhiflow/data/zhiflow", null, null, null, null, null);
        DataSourceConfig cfg = svc.buildFromWizard(DbType.H2, params);
        // Relative path resolved against user.dir (test tempDir here)
        assertTrue(cfg.url().contains(tempDir.toString().replace("\\", "/")));
    }

    @Test
    void buildFromWizard_embedded_defaultPathLandsInDatabaseFolder() {
        DataSourceConfigService svc = newService();
        // No filePath supplied → default <baseDir>/database/zhiflow
        WizardParams params = new WizardParams(null, null, null, null, null, null);
        DataSourceConfig cfg = svc.buildFromWizard(DbType.H2, params);

        Path expectedDir = tempDir.resolve("database");
        Path expectedFile = expectedDir.resolve("zhiflow");
        // The directory must have been auto-created.
        assertTrue(Files.isDirectory(expectedDir),
                "database folder should be auto-created for embedded DBs");
        // And the resolved path inside the config must point at it.
        assertTrue(cfg.url().contains(expectedFile.toString().replace("\\", "/")));
        assertTrue(cfg.filePath().contains("/database/zhiflow"));
    }

    @Test
    void buildFromWizard_embedded_createsParentDirectoryForCustomPath() {
        DataSourceConfigService svc = newService();
        // A deeply nested custom path whose parent does not yet exist.
        Path custom = tempDir.resolve("some/deeply/nested/db/zhiflow");
        WizardParams params = new WizardParams(custom.toString(), null, null, null, null, null);

        DataSourceConfig cfg = svc.buildFromWizard(DbType.SQLITE, params);

        // Parent directory auto-created so the JDBC driver can write the file.
        assertTrue(Files.isDirectory(custom.getParent()),
                "parent directory of a custom embedded path should be auto-created");
        assertTrue(cfg.url().startsWith("jdbc:sqlite:"));
    }
}
