package fan.summer.zhiflow;

import fan.summer.zhiflow.ai.spring.AiApplication;
import fan.summer.zhiflow.api.log.LoggerBinder;
import fan.summer.zhiflow.log.Slf4jPluginLoggerBinder;
import fan.summer.zhiflow.setup.DataSourceConfig;
import fan.summer.zhiflow.setup.DataSourceConfigService;
import fan.summer.zhiflow.setup.SetupApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 4 headless entry point. Boots ZhiFlow as a loopback Spring Boot web server in one of
 * two modes, determined by the presence AND reachability of
 * {@code ~/.zhiflow/config/datasource.properties}:
 *
 * <ul>
 *   <li><b>SETUP mode</b> (config missing, or config present but the DB is unreachable): boots
 *       {@link SetupApplication} — a minimal context with only the setup wizard endpoints. No
 *       DataSource/JPA. When the DB was unreachable, the stale config is backed up to
 *       {@code .bak} first so the wizard can reappear. After the wizard completes, the process
 *       exits with {@link ExitCodes#SETUP_DONE} so the Tauri supervisor restarts into APP mode.</li>
 *   <li><b>APP mode</b> (config present and DB reachable): boots {@link AiApplication} with
 *       {@code zhiflow.mode=app} — the full context with JPA, AI, plugins.</li>
 * </ul>
 *
 * <p>Both modes bind loopback ({@code server.address=127.0.0.1} from application.yml) and accept
 * the same {@code --port} / {@code --token} CLI args. {@link fan.summer.zhiflow.web.PortAnnouncer}
 * prints {@code ZHIFLOW_PORT=<n>} in both modes, so the Tauri sidecar reads the port identically.
 */
public final class HeadlessLauncher {

    /** System property the {@code TokenAuthFilter} reads. */
    public static final String TOKEN_PROPERTY = "zhiflow.auth.token";

    /** Fixed loopback port the backend binds by default. Overridable via {@code --port=<n>}. */
    public static final String DEFAULT_PORT = "24056";

    private HeadlessLauncher() {}

    public static void main(String[] args) {
        primeLogDirectory();

        String port = DEFAULT_PORT;
        String token = "";
        for (String a : args) {
            if (a.startsWith("--port=")) {
                port = a.substring("--port=".length()).trim();
            } else if (a.startsWith("--token=")) {
                token = a.substring("--token=".length()).trim();
            }
        }
        if (!token.isBlank()) {
            System.setProperty(TOKEN_PROPERTY, token);
        }

        LoggerBinder.bind(new Slf4jPluginLoggerBinder());

        boolean configured = probeAndDecide(new DataSourceConfigService());
        startWithFallback(port, configured);
        // main() returns; the embedded Tomcat's non-daemon threads keep the JVM alive.
    }

    private static final Logger log = LoggerFactory.getLogger(HeadlessLauncher.class);

    /**
     * Startup decision: load the datasource config and probe the DB. Returns {@code true} (APP
     * mode) only when a config is loaded AND a JDBC {@code SELECT 1} succeeds. Returns
     * {@code false} (SETUP mode) when there is no config, or when the config exists but the DB is
     * unreachable — in the latter case the stale config is backed up to {@code .bak} so the wizard
     * can reappear. Non-connection exceptions (e.g. driver classpath issues) are logged and treated
     * conservatively as {@code true} to avoid deleting a possibly-good config.
     */
    static boolean probeAndDecide(DataSourceConfigService configService) {
        DataSourceConfig cfg = configService.load();
        if (cfg == null) {
            return false;
        }
        // Short JDBC login timeout so a down remote host fails fast (doesn't block startup).
        int prevTimeout = DriverManager.getLoginTimeout();
        DriverManager.setLoginTimeout(5);
        boolean reachable;
        try {
            reachable = configService.testConnection(cfg).success();
        } catch (RuntimeException e) {
            // Non-connection failure (driver missing, config corruption) — don't delete config.
            log.warn("DB probe threw (non-connection); booting APP mode conservatively: {}", e.getMessage());
            return true;
        } finally {
            DriverManager.setLoginTimeout(prevTimeout);
        }
        if (reachable) {
            return true;
        }
        log.warn("Configured DB is unreachable at startup; backing up config and falling back to SETUP mode.");
        configService.backupAndClear();
        return false;
    }

    /**
     * Boots Spring Boot on the given port, retrying on {@code --server.port=0} if the requested
     * port cannot be bound. Selects SETUP vs APP context based on {@code configured}.
     */
    private static void startWithFallback(String port, boolean configured) {
        List<String> baseArgs = new ArrayList<>();
        try {
            runSpring(baseArgs, port, configured);
        } catch (RuntimeException e) {
            if ("0".equals(port)) {
                throw e;
            }
            System.err.println("WARN: could not bind port " + port + " (" + e.getMessage()
                    + "); retrying on an OS-assigned free port (--server.port=0).");
            runSpring(baseArgs, "0", configured);
        }
    }

    private static void runSpring(List<String> baseArgs, String port, boolean configured) {
        List<String> springArgs = new ArrayList<>(baseArgs);
        springArgs.add("--server.port=" + port);
        Class<?> appClass = configured ? AiApplication.class : SetupApplication.class;
        SpringApplicationBuilder builder = new SpringApplicationBuilder(appClass);
        if (configured) {
            // APP mode marker — DataSourceAutoConfig is conditional on it.
            System.setProperty("zhiflow.mode", "app");
        }
        builder.run(springArgs.toArray(new String[0]));
    }

    private static void primeLogDirectory() {
        if (System.getProperty("zhiflow.log.dir") != null) return;
        Path logDir = Path.of(System.getProperty("user.dir"), ".zhiflow", "logs");
        try {
            Files.createDirectories(logDir);
        } catch (Exception ignored) {
        }
        System.setProperty("zhiflow.log.dir", logDir.toAbsolutePath().toString());
    }
}
