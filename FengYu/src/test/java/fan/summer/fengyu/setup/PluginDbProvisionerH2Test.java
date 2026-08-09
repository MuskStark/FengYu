package fan.summer.fengyu.setup;

import org.h2.tools.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the H2 provisioning path against a REAL in-process H2 TCP server.
 * Verifies CREATE USER / CREATE SCHEMA / GRANT actually run, and that the provisioned plugin
 * user can connect but is confined to its own schema (the DB engine enforces it).
 *
 * <p>MySQL/PostgreSQL DDL is covered at the string level by DbDialectStatementsTest; executing
 * it against live servers is a documented manual-e2e gap (no Testcontainers per spec).
 */
class PluginDbProvisionerH2Test {

    private static Server h2Server;
    private static int port;

    @BeforeAll
    static void startH2Tcp() throws Exception {
        Path dbDir = Path.of(System.getProperty("java.io.tmpdir"),
            "fengyu-provisioner-test-" + System.nanoTime());
        java.nio.file.Files.createDirectories(dbDir);
        // H2's TCP server binds loopback by default; -tcpAllowOthers is intentionally omitted
        // so the server stays unreachable off-host. H2 2.4.240 has no -tcpHost option.
        h2Server = Server.createTcpServer(
                "-tcp", "-tcpPort", "0", "-ifNotExists",
                "-baseDir", dbDir.toString()).start();
        port = h2Server.getPort();
        // Seed two host databases with the H2 default admin "sa" (blank password).
        try (Connection c = DriverManager.getConnection(
                "jdbc:h2:tcp://127.0.0.1:" + port + "/fengyu;USER=sa;PASSWORD=")) {
            c.createStatement().execute("CREATE SCHEMA IF NOT EXISTS PUBLIC");
        }
        try (Connection c = DriverManager.getConnection(
                "jdbc:h2:tcp://127.0.0.1:" + port + "/fengyu2;USER=sa;PASSWORD=")) {
            c.createStatement().execute("CREATE SCHEMA IF NOT EXISTS PUBLIC");
        }
    }

    @AfterAll
    static void stopH2Tcp() {
        if (h2Server != null) h2Server.stop();
    }

    @TempDir Path config;

    @Test
    void provisionCreatesUserSchemaGrantAndConfinesThePluginUser() throws Exception {
        DataSourceConfigService dataSources = new DataSourceConfigService(config.toString()) {
            @Override public DataSourceConfig load() {
                return new DataSourceConfig(DbType.H2,
                    "jdbc:h2:tcp://127.0.0.1:" + port + "/fengyu", "org.h2.Driver",
                    "org.hibernate.dialect.H2Dialect", "sa", "", null, "sa", "");
            }
        };
        PluginDbProvisioningStore store = new PluginDbProvisioningStore(config);
        PluginDbProvisioner provisioner = new PluginDbProvisioner(dataSources, store);

        PluginDbProvisioner.ProvisionedCredentials creds =
            provisioner.provision("fan.summer.email");

        assertEquals(DbType.H2, creds.type());
        assertNotNull(creds.username());
        assertNotNull(creds.password());
        assertTrue(creds.url().startsWith("jdbc:h2:tcp://127.0.0.1:" + port + "/"),
            "worker URL must point at the H2 TCP server: " + creds.url());
        assertTrue(provisioner.isProvisioned("fan.summer.email"));

        // The plugin user can create a table in its own schema.
        try (Connection pluginConn = DriverManager.getConnection(
                creds.url(), creds.username(), creds.password())) {
            pluginConn.createStatement().execute(
                "CREATE TABLE fengyu_fan_summer_email.PLUGIN_TABLE (id INT)");
            pluginConn.createStatement().execute(
                "INSERT INTO fengyu_fan_summer_email.PLUGIN_TABLE VALUES (1)");
        }

        // The plugin user CANNOT write to the host's PUBLIC schema (no GRANT).
        try (Connection pluginConn = DriverManager.getConnection(
                creds.url(), creds.username(), creds.password())) {
            assertThrows(java.sql.SQLException.class, () ->
                pluginConn.createStatement().execute("CREATE TABLE PUBLIC.PLUGIN_LEAK (id INT)"),
                "plugin user must NOT be able to create tables in the host's PUBLIC schema");
        }

        // Idempotent: second provision returns the SAME credentials without re-creating.
        PluginDbProvisioner.ProvisionedCredentials again =
            provisioner.provision("fan.summer.email");
        assertEquals(creds.username(), again.username());
        assertEquals(creds.password(), again.password());
    }

    @Test
    void deprovisionDropsUserAndSchemaAndRemovesStoreRecord() throws Exception {
        DataSourceConfigService dataSources = new DataSourceConfigService(config.toString()) {
            @Override public DataSourceConfig load() {
                return new DataSourceConfig(DbType.H2,
                    "jdbc:h2:tcp://127.0.0.1:" + port + "/fengyu2", "org.h2.Driver",
                    "org.hibernate.dialect.H2Dialect", "sa", "", null, "sa", "");
            }
        };
        PluginDbProvisioningStore store = new PluginDbProvisioningStore(config);
        PluginDbProvisioner provisioner = new PluginDbProvisioner(dataSources, store);

        PluginDbProvisioner.ProvisionedCredentials creds =
            provisioner.provision("fan.summer.calendar");
        assertTrue(provisioner.isProvisioned("fan.summer.calendar"));

        provisioner.deprovision("fan.summer.calendar");
        assertFalse(provisioner.isProvisioned("fan.summer.calendar"),
            "store record must be gone after deprovision");

        assertThrows(java.sql.SQLException.class, () ->
            DriverManager.getConnection(creds.url(), creds.username(), creds.password()),
            "dropped plugin user must not be able to connect");
    }

    @Test
    void failedIntentWriteRunsNoDdl() throws Exception {
        DataSourceConfig cfg = h2Config("intent_write_failure");
        DataSourceConfigService dataSources = fixedConfig(cfg);
        PluginDbProvisioningStore store = new PluginDbProvisioningStore(config) {
            @Override public void put(ProvisionedPluginDb record) {
                throw new IllegalStateException("injected store failure");
            }
        };
        PluginDbProvisioner provisioner = new PluginDbProvisioner(dataSources, store);

        DbProvisioningException error = assertThrows(DbProvisioningException.class,
            () -> provisioner.provision("fan.summer.intentfailure"));
        assertTrue(error.getMessage().contains("no database changes"));

        try (Connection admin = DriverManager.getConnection(cfg.url(), "sa", "");
                PreparedStatement query = admin.prepareStatement(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.USERS WHERE USER_NAME = ?")) {
            query.setString(1, PluginDbProvisioner.userNameFor(
                "fan.summer.intentfailure").toUpperCase(java.util.Locale.ROOT));
            try (var result = query.executeQuery()) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1),
                    "DDL must not start until the recovery record is durable");
            }
        }
    }

    @Test
    void activationWriteFailureIsRecoveredIdempotently() throws Exception {
        DataSourceConfig cfg = h2Config("activation_write_failure");
        AtomicBoolean failActivation = new AtomicBoolean(true);
        PluginDbProvisioningStore store = new PluginDbProvisioningStore(config) {
            @Override public void setStatus(String pluginId, String status) {
                if (PluginDbProvisioningStore.STATUS_ACTIVE.equals(status)
                        && failActivation.get()) {
                    throw new IllegalStateException("injected activation failure");
                }
                super.setStatus(pluginId, status);
            }
        };
        PluginDbProvisioner provisioner = new PluginDbProvisioner(fixedConfig(cfg), store);

        DbProvisioningException error = assertThrows(DbProvisioningException.class,
            () -> provisioner.provision("fan.summer.activationfailure"));
        assertTrue(error.getMessage().contains("recovery is pending"));
        PluginDbProvisioningStore.ProvisionedPluginDb pending =
            store.get("fan.summer.activationfailure");
        assertEquals(PluginDbProvisioningStore.STATUS_PROVISIONING, pending.canonicalStatus());
        assertFalse(provisioner.isProvisioned("fan.summer.activationfailure"));

        failActivation.set(false);
        assertEquals("provisioned",
            provisioner.retryIncompleteOperation("fan.summer.activationfailure"));
        PluginDbProvisioningStore.ProvisionedPluginDb active =
            store.get("fan.summer.activationfailure");
        assertTrue(active.isActive());
        try (Connection ignored = DriverManager.getConnection(
                active.url(), active.userName(), active.password())) {
            assertNotNull(ignored);
        }
    }

    @Test
    void missingConfigKeepsDeletePendingUntilRetrySucceeds() throws Exception {
        DataSourceConfig cfg = h2Config("delete_pending_recovery");
        AtomicReference<DataSourceConfig> current = new AtomicReference<>(cfg);
        DataSourceConfigService dataSources = new DataSourceConfigService(config.toString()) {
            @Override public DataSourceConfig load() { return current.get(); }
        };
        PluginDbProvisioningStore store = new PluginDbProvisioningStore(config);
        PluginDbProvisioner provisioner = new PluginDbProvisioner(dataSources, store);
        PluginDbProvisioner.ProvisionedCredentials creds =
            provisioner.provision("fan.summer.deletepending");

        current.set(null);
        provisioner.deprovision("fan.summer.deletepending");
        assertEquals("delete-pending", provisioner.status("fan.summer.deletepending"));
        assertFalse(provisioner.isProvisioned("fan.summer.deletepending"));
        try (Connection ignored = DriverManager.getConnection(
                creds.url(), creds.username(), creds.password())) {
            assertNotNull(ignored, "failed cleanup must retain usable recovery coordinates");
        }

        current.set(cfg);
        provisioner.reconcileIncompleteOperations();
        assertEquals("not-provisioned", provisioner.status("fan.summer.deletepending"));
        assertThrows(java.sql.SQLException.class, () ->
            DriverManager.getConnection(creds.url(), creds.username(), creds.password()));
    }

    @Test
    void provisionThrowsWhenAdminCredentialsMissing() {
        DataSourceConfigService dataSources = new DataSourceConfigService(config.toString()) {
            @Override public DataSourceConfig load() {
                return new DataSourceConfig(DbType.MYSQL, "jdbc:mysql://h/d",
                    "com.mysql.cj.jdbc.Driver", "org.hibernate.dialect.MySQLDialect",
                    "u", "p", null, null, null);
            }
        };
        PluginDbProvisioner provisioner =
            new PluginDbProvisioner(dataSources, new PluginDbProvisioningStore(config));
        DbProvisioningException ex = assertThrows(DbProvisioningException.class,
            () -> provisioner.provision("fan.summer.email"));
        assertTrue(ex.getMessage().toLowerCase().contains("admin"),
            "error message must explain admin credentials are required: " + ex.getMessage());
    }

    /**
     * Regression for the MySQL worker-URL bug where stripping {@code jdbc:mysql://} (with the
     * {@code //}) made {@code URI} parse the remainder as an opaque scheme, dropping host and
     * port. The fix keeps the leading {@code //} so the authority survives.
     */
    @Test
    void workerUrlForMysqlPreservesHostPortAndQuery() {
        DataSourceConfig cfg = new DataSourceConfig(DbType.MYSQL,
            "jdbc:mysql://db.example.com:3306/fengyu?useSSL=true",
            "com.mysql.cj.jdbc.Driver", "org.hibernate.dialect.MySQLDialect",
            "u", "p", null, "u", "p");
        String url = PluginDbProvisioner.workerUrlFor(cfg, "fengyu_fan_summer_email");
        assertEquals("jdbc:mysql://db.example.com:3306/fengyu_fan_summer_email?useSSL=true", url,
            "MySQL worker URL must preserve host, port, and query: " + url);
    }

    @Test
    void workerUrlForMysqlPreservesHostWhenNoPortOrQuery() {
        DataSourceConfig cfg = new DataSourceConfig(DbType.MYSQL,
            "jdbc:mysql://db.example.com/fengyu",
            "com.mysql.cj.jdbc.Driver", "org.hibernate.dialect.MySQLDialect",
            "u", "p", null, "u", "p");
        String url = PluginDbProvisioner.workerUrlFor(cfg, "fengyu_fan_summer_email");
        assertEquals("jdbc:mysql://db.example.com/fengyu_fan_summer_email", url,
            "MySQL worker URL must preserve host when port/query are absent: " + url);
    }

    @Test
    void redactPasswordMasksIdentifiedByLiteral() {
        String sql = "CREATE USER IF NOT EXISTS 'u'@'127.0.0.1' IDENTIFIED BY 'S3cr3t!'";
        String redacted = PluginDbProvisioner.redactPassword(sql);
        assertTrue(redacted.contains("IDENTIFIED BY '***'"),
            "redacted SQL must keep the keyword with masked literal: " + redacted);
        assertFalse(redacted.contains("S3cr3t!"),
            "redacted SQL must NOT contain the plaintext password: " + redacted);
    }

    @Test
    void redactPasswordMasksPasswordLiteral() {
        String sql = "CREATE ROLE r PASSWORD 'hunter2'";
        String redacted = PluginDbProvisioner.redactPassword(sql);
        assertTrue(redacted.contains("PASSWORD '***'"),
            "redacted SQL must mask the PASSWORD literal: " + redacted);
        assertFalse(redacted.contains("hunter2"),
            "redacted SQL must NOT contain the plaintext password: " + redacted);
    }

    private static DataSourceConfig h2Config(String database) {
        return new DataSourceConfig(DbType.H2,
            "jdbc:h2:tcp://127.0.0.1:" + port + "/" + database, "org.h2.Driver",
            "org.hibernate.dialect.H2Dialect", "sa", "", null, "sa", "");
    }

    private DataSourceConfigService fixedConfig(DataSourceConfig cfg) {
        return new DataSourceConfigService(config.toString()) {
            @Override public DataSourceConfig load() { return cfg; }
        };
    }
}
