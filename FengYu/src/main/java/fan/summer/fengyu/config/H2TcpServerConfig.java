package fan.summer.fengyu.config;

import fan.summer.fengyu.runtime.RuntimePaths;
import fan.summer.fengyu.setup.DataSourceConfig;
import fan.summer.fengyu.setup.DataSourceConfigService;
import fan.summer.fengyu.setup.DbType;
import org.h2.tools.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Starts the in-process H2 TCP server (loopback, dynamic port) so that:
 * <ol>
 *   <li>the host's own DataSource can connect via {@code tcp://} (no exclusive file lock), and</li>
 *   <li>plugin workers provisioned by {@code PluginDbProvisioner} can attach to per-plugin
 *       schemas on the SAME running server (true DB-level RBAC isolation).</li>
 * </ol>
 *
 * <p><b>Lifecycle ordering (critical).</b> {@code HeadlessLauncher.probeAndDecide} opens a JDBC
 * connection BEFORE Spring boots. So {@link #startIfNeeded(DataSourceConfigService)} is invoked
 * from {@code HeadlessLauncher.main} BEFORE the probe, not from a Spring {@code @PostConstruct}.
 * This class's Spring identity exists only so {@link #stop()} runs on context shutdown via
 * {@code @PreDestroy}.
 *
 * <p><b>Loopback binding.</b> H2 2.4.240 has NO {@code -tcpHost} flag (passing it throws
 * {@code JdbcSQLFeatureNotSupportedException}) and its {@code Server} API binds via
 * {@code NetUtils.createServerSocket(port, ssl)} which takes no host parameter and defaults to a
 * LAN address. To force loopback we set the H2 system property {@code h2.bindAddress} BEFORE
 * creating the server — {@code NetUtils.createServerSocketTry} reads
 * {@code SysProperties.BIND_ADDRESS} via {@code getBindAddress()}, which makes it bind
 * {@code 127.0.0.1} (verified: {@code server.getURL()} then returns {@code tcp://localhost:...}
 * instead of the machine's LAN address). {@code -tcpAllowOthers} is intentionally omitted: it is
 * a no-arg toggle and the loopback bind already keeps the server unreachable off-host.
 *
 * <p>On first start the host's persisted {@code db.url} is migrated from {@code file:} to
 * {@code tcp://127.0.0.1:<port>/...}; the Hibernate dialect ({@code H2Dialect}) is URL-scheme
 * agnostic so this is safe. The chosen port is recorded to {@code <config>/h2-server.properties}
 * for diagnostics.
 */
@Configuration
@ConditionalOnProperty(name = "fengyu.mode", havingValue = "app")
public class H2TcpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(H2TcpServerConfig.class);
    private static final Pattern H2_FILE_PATH = Pattern.compile("jdbc:h2:file:(.+)");
    private static final Pattern H2_TCP_PATH = Pattern.compile("jdbc:h2:tcp://[^/]+/(.+)");

    private static volatile Server server;
    private static volatile int boundPort;

    /**
     * Starts the H2 TCP server if the host DB is H2 and no server is running. Rewrites the host's
     * {@code db.url} from {@code file:} to {@code tcp://} on the dynamic port. Returns the bound
     * port, or 0 if no server was started (non-H2 / unconfigured / start failed).
     */
    public static int startIfNeeded(DataSourceConfigService dataSources) {
        DataSourceConfig cfg = dataSources.load();
        if (cfg == null || cfg.type() != DbType.H2) {
            return 0;
        }
        if (server != null && server.isRunning(false)) {
            return boundPort;
        }
        try {
            // OS-assigned loopback port: bind a ServerSocket to 0, read the port, close, hand to H2.
            int port;
            try (ServerSocket probe = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
                port = probe.getLocalPort();
            }
            // Force H2 to bind loopback. H2 2.4.240 has no -tcpHost flag and its Server API takes
            // no host parameter; NetUtils.createServerSocketTry honors SysProperties.BIND_ADDRESS.
            System.setProperty("h2.bindAddress", "127.0.0.1");
            server = Server.createTcpServer(
                    "-tcp", "-tcpPort", String.valueOf(port),
                    "-ifNotExists").start();
            boundPort = server.getPort();
            log.info("Started H2 TCP server on {} (bound port {}, requested {})",
                    server.getURL(), boundPort, port);

            String newUrl = rewriteUrlToTcp(cfg.url(), boundPort);
            if (!newUrl.equals(cfg.url())) {
                DataSourceConfig migrated = new DataSourceConfig(cfg.type(), newUrl, cfg.driver(),
                        cfg.dialect(), cfg.username(), cfg.password(), cfg.filePath(),
                        cfg.adminUsername(), cfg.adminPassword());
                dataSources.save(migrated);
            }
            recordPort(boundPort);
            return boundPort;
        } catch (Exception e) {
            // Non-fatal: fall through to the original URL. probeAndDecide then either succeeds in
            // single-process file mode or fails and the setup wizard reappears.
            log.error("Failed to start H2 TCP server; continuing without it: {}", e.getMessage(), e);
            return 0;
        }
    }

    /** The port the H2 TCP server bound in this JVM, or 0 if none. */
    public static int port() {
        return boundPort;
    }

    /** Spring-owned shutdown: stops the server on context close. */
    @PreDestroy
    public void stop() {
        stopInternal();
    }

    private static void stopInternal() {
        if (server != null) {
            try {
                server.stop();
            } catch (Exception e) {
                log.debug("H2 TCP server stop failed: {}", e.getMessage());
            }
            server = null;
            boundPort = 0;
        }
    }

    /** Test-only: stop + reset static state between tests. */
    static void stopForTest() {
        stopInternal();
    }

    private static String rewriteUrlToTcp(String url, int port) {
        Matcher file = H2_FILE_PATH.matcher(url);
        if (file.matches()) {
            return "jdbc:h2:tcp://127.0.0.1:" + port + "/" + file.group(1);
        }
        Matcher tcp = H2_TCP_PATH.matcher(url);
        if (tcp.matches()) {
            return "jdbc:h2:tcp://127.0.0.1:" + port + "/" + tcp.group(1);
        }
        return url;
    }

    private static void recordPort(int port) {
        try {
            Path file = RuntimePaths.configDirectory(RuntimePaths.root()).resolve("h2-server.properties");
            Files.createDirectories(file.getParent());
            // h2-server.properties is non-secret (diagnostics only); the parent config directory is
            // already owner-only-protected whenever DataSourceConfigService writes datasource.properties.
            // SensitiveFilePermissions is package-private to fan.summer.fengyu.setup and intentionally
            // not broadened for a non-secret diagnostics file.
            Properties props = new Properties();
            props.setProperty("h2.tcp.port", String.valueOf(port));
            props.setProperty("h2.tcp.host", "127.0.0.1");
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "FengYu H2 in-process TCP server (non-secret, diagnostics)");
            }
        } catch (IOException e) {
            log.debug("Could not record H2 server port: {}", e.getMessage());
        }
    }
}
