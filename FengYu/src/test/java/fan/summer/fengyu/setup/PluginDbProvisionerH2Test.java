package fan.summer.fengyu.setup;

import org.h2.tools.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

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
}
