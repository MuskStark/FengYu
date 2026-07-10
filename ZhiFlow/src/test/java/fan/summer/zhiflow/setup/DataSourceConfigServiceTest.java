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

    @Test
    void backupAndClear_movesConfigToBak() {
        DataSourceConfigService svc = newService();
        // Seed a real config file.
        WizardParams params = new WizardParams(
                tempDir.resolve("database/zhiflow").toString(), null, null, null, null, null);
        svc.save(svc.buildFromWizard(DbType.H2, params));

        java.nio.file.Path bak = svc.backupAndClear();

        assertNotNull(bak, "should return the backup path");
        assertFalse(Files.exists(svc.configFileForTest()),
                "original config should be gone");
        assertTrue(Files.exists(bak), "backup file should exist");
        assertTrue(bak.getFileName().toString().endsWith(".bak"),
                "backup name should end with .bak, got: " + bak);
    }

    @Test
    void backupAndClear_whenBakExists_appendsTimestamp() throws Exception {
        DataSourceConfigService svc = newService();
        WizardParams params = new WizardParams(
                tempDir.resolve("database/zhiflow").toString(), null, null, null, null, null);

        // First backup.
        svc.save(svc.buildFromWizard(DbType.H2, params));
        java.nio.file.Path firstBak = svc.backupAndClear();
        assertNotNull(firstBak);

        // Second backup of a re-saved config.
        svc.save(svc.buildFromWizard(DbType.SQLITE, params));
        java.nio.file.Path secondBak = svc.backupAndClear();
        assertNotNull(secondBak);

        assertNotEquals(firstBak, secondBak,
                "second backup must not overwrite the first");
        assertTrue(Files.exists(firstBak), "first backup must still exist");
        assertTrue(Files.exists(secondBak), "second backup must exist");
        assertTrue(secondBak.getFileName().toString().matches(".*\\.bak\\.\\d+"),
                "second backup name should be .bak.<timestamp>, got: " + secondBak);
    }

    @Test
    void backupAndClear_whenFileMissing_returnsNullNoThrow() {
        DataSourceConfigService svc = newService();
        java.nio.file.Path bak = svc.backupAndClear();
        assertNull(bak, "no file to back up → null");
    }
}
