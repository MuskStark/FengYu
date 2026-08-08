package fan.summer.fengyu.config;

import fan.summer.fengyu.setup.DataSourceConfig;
import fan.summer.fengyu.setup.DataSourceConfigService;
import fan.summer.fengyu.setup.DbType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2TcpServerConfigTest {

    @TempDir Path temp;

    @AfterEach
    void stopServer() {
        H2TcpServerConfig.stopForTest();
    }

    @Test
    void startsOnLoopbackWithDynamicPortWhenHostDbIsH2() throws Exception {
        DataSourceConfigService svc = new DataSourceConfigService(temp.toString());
        svc.save(new DataSourceConfig(DbType.H2, "jdbc:h2:file:" + temp.resolve("fengyu"),
            "org.h2.Driver", "org.hibernate.dialect.H2Dialect", "sa", "", null, "sa", ""));

        int port = H2TcpServerConfig.startIfNeeded(svc);
        assertTrue(port > 0, "H2 TCP server should have started on a dynamic port");

        DataSourceConfig reloaded = svc.load();
        assertTrue(reloaded.url().startsWith("jdbc:h2:tcp://127.0.0.1:" + port + "/"),
            "host url must be rewritten to tcp://: " + reloaded.url());

        try (Connection c = DriverManager.getConnection(reloaded.url(), "sa", "")) {
            ResultSet rs = c.createStatement().executeQuery("SELECT 1");
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void doesNotStartWhenHostDbIsNotH2() {
        DataSourceConfigService svc = new DataSourceConfigService(temp.toString());
        svc.save(new DataSourceConfig(DbType.POSTGRESQL, "jdbc:postgresql://h/d",
            "org.postgresql.Driver", "org.hibernate.dialect.PostgreSQLDialect",
            "u", "p", null, null, null));
        assertEquals(0, H2TcpServerConfig.startIfNeeded(svc),
            "no H2 TCP server should start for a PostgreSQL host");
    }

    @Test
    void doesNotStartWhenHostDbIsUnconfigured() {
        DataSourceConfigService svc = new DataSourceConfigService(temp.toString());
        assertEquals(0, H2TcpServerConfig.startIfNeeded(svc));
    }

    @Test
    void startingTwiceIsIdempotentAndReusesPort() {
        DataSourceConfigService svc = new DataSourceConfigService(temp.toString());
        svc.save(new DataSourceConfig(DbType.H2, "jdbc:h2:file:" + temp.resolve("fengyu2"),
            "org.h2.Driver", "org.hibernate.dialect.H2Dialect", "sa", "", null, "sa", ""));
        int first = H2TcpServerConfig.startIfNeeded(svc);
        int second = H2TcpServerConfig.startIfNeeded(svc);
        assertEquals(first, second, "second start must reuse the running server's port");
    }

    @Test
    void rewrittenTcpUrlUsesDifferentPortThan24056() {
        DataSourceConfigService svc = new DataSourceConfigService(temp.toString());
        svc.save(new DataSourceConfig(DbType.H2, "jdbc:h2:file:" + temp.resolve("fengyu3"),
            "org.h2.Driver", "org.hibernate.dialect.H2Dialect", "sa", "", null, "sa", ""));
        int port = H2TcpServerConfig.startIfNeeded(svc);
        assertNotEquals(24056, port);
    }
}
