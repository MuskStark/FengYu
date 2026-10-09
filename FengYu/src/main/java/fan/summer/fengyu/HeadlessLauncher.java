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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.PortInUseException;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.BindException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

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
 * prints {@code FENGYU_PORT=<n>} in both modes, so the desktop (Electron) sidecar reads the port
 * identically.
 */
public final class HeadlessLauncher {

    /** System property the {@code TokenAuthFilter} reads. */
    public static final String TOKEN_PROPERTY = "fengyu.auth.token";
    public static final String TOKEN_ENVIRONMENT = "FENGYU_AUTH_TOKEN";

    /**
     * System property set ONLY by cache-training launches (CI packaging or the desktop's
     * background trainer). Arms {@link TrainingExitListener}: the JVM exits on its own a
     * few seconds after ready so the dump flag passed alongside it
     * ({@code -XX:AOTCacheOutput} / {@code -XX:ArchiveClassesAtExit}) writes its archive
     * at exit. A self-exit is the only cross-platform trigger — Windows has no graceful
     * termination signal a trainer script could send, and a hard TerminateProcess skips
     * the dump entirely. Never set on production launches.
     */
    static final String TRAINING_EXIT_PROPERTY = "fengyu.aot.training-exit";

    /** Fixed loopback port the backend binds by default. Overridable via {@code --port=<n>}. */
    public static final String DEFAULT_PORT = "24056";

    static final String MAC_UI_ELEMENT_PROPERTY = "apple.awt.UIElement";

    static {
        // Must run before primeRuntimeDirectories: that call normalizes ROOT_PROPERTY by setting
        // it unconditionally, after which an operator-provided pin is indistinguishable from the
        // programmatic default. DataSourceConfigService needs exactly that distinction to keep a
        // pinned runtime root (every desktop launch, any explicit -Dfengyu.runtime.dir) isolated
        // instead of silently adopting a legacy config from <cwd> or ~/.fengyu.
        System.setProperty(RuntimePaths.PINNED_MARKER_PROPERTY,
                Boolean.toString(System.getProperty(RuntimePaths.ROOT_PROPERTY) != null));
        primeRuntimeDirectories(RuntimePaths.root());
    }

    private HeadlessLauncher() {}

    public static void main(String[] args) {
        configureDesktopPlatform(
                Boolean.parseBoolean(System.getProperty("fengyu.desktop")),
                System.getProperty("os.name", ""));
        LaunchArgs parsed = parseArgs(args, System.getenv().getOrDefault(TOKEN_ENVIRONMENT, ""));
        String port = parsed.port();
        if (!parsed.token().isBlank()) {
            System.setProperty(TOKEN_PROPERTY, parsed.token());
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
     * Parsed {@code --port}/{@code --token} CLI arguments (package-private for unit testing).
     *
     * @param port              the requested server port (default {@link #DEFAULT_PORT})
     * @param token             the effective launch token (CLI value overrides the
     *                          {@code FENGYU_AUTH_TOKEN} environment default)
     * @param explicitEmptyToken {@code true} when {@code --token=} was passed with a blank
     *                          value — auth stays disabled, but loudly (see {@link #parseArgs})
     */
    record LaunchArgs(String port, String token, boolean explicitEmptyToken) {}

    /**
     * Parses the launcher CLI. Pure parsing lives here (not {@code main}) so the warning
     * behaviour is unit-testable:
     * <ul>
     *   <li>Unknown {@code --}flags are surfaced by NAME only ({@link #unknownOptionLabel}) —
     *       echoing the raw argument would print a mistyped token's VALUE (e.g.
     *       {@code --Token=<secret>}) to stderr, which launch scripts may redirect to files.</li>
     *   <li>An explicitly empty {@code --token=} warns that the API stays UNPROTECTED instead
     *       of silently booting auth-disabled.</li>
     * </ul>
     */
    static LaunchArgs parseArgs(String[] args, String envToken) {
        String port = DEFAULT_PORT;
        String token = envToken == null ? "" : envToken.trim();
        boolean explicitEmptyToken = false;
        for (String a : args) {
            if (a.startsWith("--port=")) {
                port = a.substring("--port=".length()).trim();
            } else if (a.startsWith("--token=")) {
                token = a.substring("--token=".length()).trim();
                explicitEmptyToken = token.isBlank();
            } else if (a.startsWith("--")) {
                // Surface typos like `--ports=` or `--Token=` instead of silently ignoring
                // them; a misnamed auth token would otherwise leave the API unprotected.
                System.err.println("WARN: ignoring unknown option: " + unknownOptionLabel(a));
            }
        }
        if (explicitEmptyToken) {
            System.err.println("WARN: --token= was passed with an empty value — the API stays "
                    + "UNPROTECTED (token auth disabled). Pass a real token or drop the flag.");
        }
        return new LaunchArgs(port, token, explicitEmptyToken);
    }

    /** The flag name only ({@code --Token=<secret>} → {@code --Token}); never the value. */
    static String unknownOptionLabel(String arg) {
        int eq = arg.indexOf('=');
        return eq >= 0 ? arg.substring(0, eq) : arg;
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
        // main() started the H2 TCP server BEFORE this probe (embedded-DB URLs need it).
        // Tear it back down here: SETUP mode boots SetupApplication, which does not scan
        // the config package, so the server's @PreDestroy would never run there and a
        // lifecycle-owner-less loopback listener would outlive the decision.
        H2TcpServerConfig.stopIfRunning();
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
        // Phase timing (P2): record every startup step and log the slowest once ready, so
        // lazy-initialization decisions target measured offenders (the same instance backs
        // Boot's /actuator/startup when the actuator is present).
        org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup startup =
                new org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup(
                        fan.summer.fengyu.startup.StartupTimelineLogger.DEFAULT_CAPACITY);
        builder.applicationStartup(startup);
        builder.listeners(new fan.summer.fengyu.startup.StartupTimelineLogger(
                startup, System.currentTimeMillis()));
        if (Boolean.getBoolean(TRAINING_EXIT_PROPERTY)) {
            // See TRAINING_EXIT_PROPERTY: training launches exit themselves shortly after
            // ready so the cache dump lands; never armed for production boots.
            builder.listeners(new TrainingExitListener());
        }
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
     * Exits a cache-training launch {@link #EXIT_DELAY_MILLIS} after
     * {@link ApplicationReadyEvent}: the JVM then unwinds through its normal exit path,
     * which is what makes the AOT/CDS dump flag write the archive. The delay lets the
     * ready-time listeners (seeders) and the first health responses run so their classes
     * are captured too. Daemon thread + {@link InterruptedException} restore so a JVM
     * that is told to quit before the delay elapses still exits promptly.
     */
    static final class TrainingExitListener implements ApplicationListener<ApplicationReadyEvent> {

        /** Grace after ready, in milliseconds. */
        static final long EXIT_DELAY_MILLIS = 3_000;

        private final long delayMillis;
        /** Injectable exit so unit tests never kill the test JVM. */
        private final IntConsumer exit;

        TrainingExitListener() {
            this(EXIT_DELAY_MILLIS, status -> System.exit(status));
        }

        TrainingExitListener(long delayMillis, IntConsumer exit) {
            this.delayMillis = delayMillis;
            this.exit = exit;
        }

        @Override
        public void onApplicationEvent(ApplicationReadyEvent event) {
            log.info("AOT training launch: exiting in {} ms so the cache dump is written", delayMillis);
            Thread exitTimer = new Thread(() -> {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exit.accept(ExitCodes.SETUP_DONE);
            }, "fengyu-aot-training-exit");
            exitTimer.setDaemon(true);
            exitTimer.start();
        }
    }

    /**
     * Safety-critical defaults reasserted programmatically as defense-in-depth. The shaded jar
     * packages {@code application.yml} and — since the shade spring.factories union fix
     * ({@code fan.summer.fengyu.build.SpringFactoriesUnion}, which restores the ConfigData
     * listener the AppendingTransformer used to drop) — loads it again; these loopback/limits
     * invariants are still important enough to also pin here, so a future change to
     * application.yml alone cannot silently restore the Spring Boot defaults (wildcard bind
     * address, 1 MB multipart limit). Writable plugin, skill, and transient runtime
     * directories are derived from the same program runtime root.
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

    /** Package-private for direct unit testing of the fallback behavior. */
    static void primeRuntimeDirectories(Path root) {
        System.setProperty(RuntimePaths.ROOT_PROPERTY, root.toString());
        Path logDir = RuntimePaths.logDirectory(root);
        try {
            Files.createDirectories(logDir);
        } catch (Exception e) {
            // Unwritable root (read-only cwd, sandboxed launch): degrade to a temp log dir
            // instead of silently pointing logback at a directory that will never exist.
            // System.err on purpose — the logging system is what this is configuring.
            Path fallback = Path.of(System.getProperty("java.io.tmpdir"), "fengyu-logs");
            try {
                Files.createDirectories(fallback);
                logDir = fallback;
            } catch (Exception deeper) {
                logDir = Path.of(System.getProperty("java.io.tmpdir"));
                System.err.println("WARN: cannot create " + fallback + " (" + deeper.getMessage()
                        + "); logging falls back to " + logDir);
            }
            System.err.println("WARN: cannot create log directory " + RuntimePaths.logDirectory(root)
                    + " (" + e.getMessage() + "); logging falls back to " + logDir);
        }
        System.setProperty("fengyu.log.dir", logDir.toString());
    }
}
