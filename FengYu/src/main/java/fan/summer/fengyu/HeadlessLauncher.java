package fan.summer.fengyu;

import fan.summer.fengyu.config.H2TcpServerConfig;
import fan.summer.fengyu.plugin.runtime.PluginProcessManager;
import fan.summer.fengyu.runtime.RuntimePaths;
import fan.summer.fengyu.setup.DataSourceConfig;
import fan.summer.fengyu.setup.DataSourceConfigService;
import fan.summer.fengyu.setup.SetupApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.PortInUseException;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.BindException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase 4 headless entry point. Boots FengYu as a loopback Spring Boot web server in one of
 * two modes, determined by the presence AND reachability of
 * {@code <programWorkingDirectory>/.fengyu/config/datasource.properties} (or
 * {@code fengyu.runtime.dir}):
 *
 * <ul>
 *   <li><b>SETUP mode</b> (config missing, or config present but the DB is unreachable): boots
 *       {@link SetupApplication} — a minimal context with only the setup wizard endpoints. No
 *       DataSource/JPA. When the DB was unreachable, the stale config is backed up to
 *       {@code .bak} first so the wizard can reappear. After the wizard completes, the process
 *       exits with {@link ExitCodes#SETUP_DONE} so the desktop supervisor restarts into APP mode.</li>
 *   <li><b>APP mode</b> (config present and DB reachable): boots {@link FengYuApplication} with
 *       {@code fengyu.mode=app} — the full context with JPA, AI, plugins.</li>
 * </ul>
 *
 * <p>Both modes bind loopback ({@code server.address=127.0.0.1} from application.yml) and accept
 * the same {@code --port} / {@code --token} CLI args. {@link fan.summer.fengyu.web.PortAnnouncer}
 * prints {@code FENGYU_PORT=<n>} in both modes, so the Tauri sidecar reads the port identically.
 */
public final class HeadlessLauncher {

    /** System property the {@code TokenAuthFilter} reads. */
    public static final String TOKEN_PROPERTY = "fengyu.auth.token";
    public static final String TOKEN_ENVIRONMENT = "FENGYU_AUTH_TOKEN";

    /** Fixed loopback port the backend binds by default. Overridable via {@code --port=<n>}. */
    public static final String DEFAULT_PORT = "24056";

    static final String MAC_UI_ELEMENT_PROPERTY = "apple.awt.UIElement";

    static {
        primeRuntimeDirectories(RuntimePaths.root());
    }

    private HeadlessLauncher() {}

    public static void main(String[] args) {
        configureDesktopPlatform(
                Boolean.parseBoolean(System.getProperty("fengyu.desktop")),
                System.getProperty("os.name", ""));
        String port = DEFAULT_PORT;
        String token = System.getenv().getOrDefault(TOKEN_ENVIRONMENT, "").trim();
        for (String a : args) {
            if (a.startsWith("--port=")) {
                port = a.substring("--port=".length()).trim();
            } else if (a.startsWith("--token=")) {
                token = a.substring("--token=".length()).trim();
            } else if (a.startsWith("--")) {
                // Surface typos like `--ports=` or `--Token=` instead of silently ignoring
                // them; a misnamed auth token would otherwise leave the API unprotected.
                System.err.println("WARN: ignoring unknown option: " + a);
            }
        }
        if (!token.isBlank()) {
            System.setProperty(TOKEN_PROPERTY, token);
        }

        DataSourceConfigService configService = new DataSourceConfigService();
        // H2 TCP server must start BEFORE probeAndDecide: the probe opens a JDBC connection, and
        // in H2 server mode that connection is tcp:// and needs the server already listening.
        // No-op for non-H2 / SETUP-mode hosts.
        H2TcpServerConfig.startIfNeeded(configService);
        boolean configured = probeAndDecide(configService);
        startWithFallback(port, configured);
        // main() returns; the embedded Tomcat's non-daemon threads keep the JVM alive.
    }

    /**
     * Prevent a desktop-mode backend from acquiring a second Dock icon on macOS when its
     * {@code java.awt.Robot} computer-use driver initializes. The UIElement property keeps AWT
     * headful, so screen capture and input injection remain available. Windows and Linux are left
     * untouched, and an explicit caller-provided Apple setting is respected.
     */
    static void configureDesktopPlatform(boolean desktop, String osName) {
        if (desktop && osName.toLowerCase(java.util.Locale.ROOT).contains("mac")
                && System.getProperty(MAC_UI_ELEMENT_PROPERTY) == null) {
            System.setProperty(MAC_UI_ELEMENT_PROPERTY, "true");
        }
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
            if ("0".equals(port) || !isPortBindFailure(e)) {
                throw e;
            }
            System.err.println("WARN: could not bind port " + port + " (" + e.getMessage()
                    + "); retrying on an OS-assigned free port (--server.port=0).");
            runSpring(baseArgs, "0", configured);
        }
    }

    static boolean isPortBindFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PortInUseException || cause instanceof BindException) {
                return true;
            }
        }
        return false;
    }

    private static void runSpring(List<String> baseArgs, String port, boolean configured) {
        List<String> springArgs = new ArrayList<>(baseArgs);
        springArgs.add("--server.port=" + port);
        Class<?> appClass = configured ? FengYuApplication.class : SetupApplication.class;
        SpringApplicationBuilder builder = new SpringApplicationBuilder(appClass);
        builder.properties(runtimeDefaults());
        if (Boolean.parseBoolean(System.getProperty("fengyu.desktop"))) {
            // Desktop mode drives the real screen (computer_use tools via java.awt.Robot).
            // Spring Boot defaults to headless, which would make every AWT call fail; the
            // Robot-based tools degrade gracefully on truly display-less machines anyway.
            builder.headless(false);
        }
        if (configured) {
            // APP mode marker — DataSourceAutoConfig is conditional on it.
            System.setProperty("fengyu.mode", "app");
        }
        ConfigurableApplicationContext context = builder.run(springArgs.toArray(new String[0]));
        // Plugin workers are out-of-process JVMs that the OS does not auto-reap on macOS/Windows
        // (Linux bwrap --die-with-parent handles it). Their teardown lives behind
        // PluginProcessManager.@PreDestroy, which Spring's own shutdown hook runs on graceful exit.
        // This explicit hook is a belt-and-braces backstop: if the JVM is signaled mid-shutdown and
        // Spring's hook does not complete, this one still reaches PluginProcessManager.close() and
        // destroys every tracked worker so no orphaned worker JVM survives the host (orphaned
        // workers hold embedded-DB file locks and block database deletion). SETUP mode has no plugin
        // beans, so the hook is APP-mode only.
        if (configured) {
            registerWorkerShutdownHook(context);
        }
    }

    /**
     * Install a JVM-level shutdown hook that closes the Spring context (running every @PreDestroy,
     * including {@link PluginProcessManager#close()}) and then re-asserts worker teardown directly
     * against the manager. {@code close()} is idempotent — running it twice is safe. The hook is a
     * daemon thread so it never blocks JVM exit on its own.
     */
    private static void registerWorkerShutdownHook(ConfigurableApplicationContext context) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                // Graceful path first: triggers @PreDestroy across all beans.
                if (context.isActive()) context.close();
            } catch (Exception e) {
                log.warn("Spring context close during shutdown hook failed: {}", e.getMessage());
            }
            // Direct backstop: even if context.close() threw or was incomplete, kill the workers.
            try {
                context.getBean(PluginProcessManager.class).close();
            } catch (Exception e) {
                log.debug("PluginProcessManager.close() during shutdown hook unavailable: {}", e.getMessage());
            }
        }, "fengyu-worker-shutdown"));
    }

    /**
     * Safety-critical defaults reasserted programmatically as defense-in-depth. The shaded jar
     * DOES package {@code application.yml} (it sets {@code server.address=127.0.0.1} and the
     * 128 MB multipart limits), but these loopback/limits invariants are important enough to
     * also pin here, so a future change to application.yml alone cannot silently restore the
     * Spring Boot defaults (wildcard bind address, 1 MB multipart limit). Writable plugin, skill,
     * and transient runtime directories are derived from the same program runtime root.
     */
    static Map<String, Object> runtimeDefaults() {
        return runtimeDefaults(RuntimePaths.root());
    }

    static Map<String, Object> runtimeDefaults(Path root) {
        return Map.of(
                "server.address", "127.0.0.1",
                "spring.servlet.multipart.max-file-size", "128MB",
                "spring.servlet.multipart.max-request-size", "128MB",
                "fengyu.plugins.directory", RuntimePaths.pluginDirectory(root).toString(),
                "fengyu.plugins.data-directory", RuntimePaths.pluginDataDirectory(root).toString(),
                "fengyu.skills.directory", RuntimePaths.skillDirectory(root).toString(),
                "fengyu.runtime-files.directory", RuntimePaths.runtimeFilesDirectory(root).toString());
    }

    private static void primeRuntimeDirectories(Path root) {
        System.setProperty(RuntimePaths.ROOT_PROPERTY, root.toString());
        Path logDir = RuntimePaths.logDirectory(root);
        try {
            Files.createDirectories(logDir);
        } catch (Exception ignored) {
        }
        System.setProperty("fengyu.log.dir", logDir.toString());
    }
}
