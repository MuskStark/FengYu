package fan.summer.fengyu.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataSourceConfigAdminCredentialsTest {
    @TempDir Path temp;

    @Test
    void adminCredentialsRoundTripAndAreEncryptedOnDisk() throws Exception {
        DataSourceConfigService svc = new DataSourceConfigService(temp.toString());
        DataSourceConfig cfg = new DataSourceConfig(DbType.POSTGRESQL,
            "jdbc:postgresql://db:5432/fengyu", "org.postgresql.Driver",
            "org.hibernate.dialect.PostgreSQLDialect", "fengyu", "secret", null,
            "fengyu_admin", "admin-secret");
        svc.save(cfg);

        Properties raw = svc.readRawForTest();
        assertEquals("fengyu_admin", raw.getProperty("db.admin.username"));
        assertTrue(raw.getProperty("db.admin.password").startsWith("ENC("),
            "admin password must be encrypted at rest: " + raw.getProperty("db.admin.password"));
        assertFalse(raw.getProperty("db.admin.password").contains("admin-secret"),
            "plaintext admin secret must not appear on disk");

        DataSourceConfig loaded = svc.load();
        assertEquals("fengyu_admin", loaded.adminUsername());
        assertEquals("admin-secret", loaded.adminPassword());
        assertEquals("secret", loaded.password());
    }

    @Test
    void missingAdminCredentialsLoadAsNullNotBlank() {
        DataSourceConfigService svc = new DataSourceConfigService(temp.toString());
        svc.save(new DataSourceConfig(DbType.SQLITE, "jdbc:sqlite:app.db", "org.sqlite.JDBC",
            "org.hibernate.community.dialect.SQLiteDialect", "", "", "/app.db", null, null));
        DataSourceConfig loaded = svc.load();
        assertNull(loaded.adminUsername());
        assertNull(loaded.adminPassword());
    }

    @Test
    void buildFromWizardCarriesAdminParamsThrough() {
        DataSourceConfigService svc = new DataSourceConfigService(temp.toString());
        WizardParams params = new WizardParams(null, "db", 5432, "fengyu",
            "fengyu", "secret", "fengyu_admin", "admin-secret");
        DataSourceConfig cfg = svc.buildFromWizard(DbType.POSTGRESQL, params);
        assertEquals("fengyu_admin", cfg.adminUsername());
        assertEquals("admin-secret", cfg.adminPassword());
    }
}
