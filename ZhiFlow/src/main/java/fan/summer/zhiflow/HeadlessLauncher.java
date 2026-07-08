package fan.summer.zhiflow;

import fan.summer.zhiflow.ai.spring.AiApplication;
import fan.summer.zhiflow.api.log.LoggerBinder;
import fan.summer.zhiflow.database.DatabaseInit;
import fan.summer.zhiflow.log.Slf4jPluginLoggerBinder;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 1 headless entry point — boots ZhiFlow as a loopback Spring Boot web server, no JavaFX.
 *
 * <p>Usage: {@code java -jar ZhiFlow.jar --port=<n> --token=<t>}
 * <ul>
 *   <li>{@code --port=<n>} — bind port; defaults to {@link #DEFAULT_PORT} ({@value DEFAULT_PORT}).
 *       {@code 0} asks the OS for a free port. The chosen port is printed as
 *       {@code ZHIFLOW_PORT=<n>} to stdout (by {@code PortAnnouncer}) for the Tauri sidecar to
 *       read, so the desktop shell works whether the fixed port binds or falls back.</li>
 *   <li>{@code --token=<t>} — per-launch auth token; when set, every request must carry it as the
 *       {@code X-ZhiFlow-Token} header (or {@code ?token=} for the SSE stream). When blank, auth is
 *       disabled (browser-dev convenience).</li>
 * </ul>
 *
 * <p>This class only performs the work that must happen <em>before</em> the Spring context: prime
 * the log directory, install the plugin logger binder, and initialize H2 (so {@code AiConfigService}
 * can read settings while beans are built). Everything else is standard Spring Boot — the CLI args
 * are translated to Spring properties ({@code server.port} / {@code server.address}) and handed to
 * {@link SpringApplicationBuilder}. Port output and AI-backend init are handled by beans
 * ({@code PortAnnouncer}, {@code AiBackendInitializer}); the Boot shutdown hook closes the context.
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

        // Pre-context infra: plugin logging bridge + H2 (AiConfigService reads it during bean build).
        LoggerBinder.bind(new Slf4jPluginLoggerBinder());
        DatabaseInit.init();

        // Standard Spring Boot bootstrap — loopback SERVLET web server. If the requested fixed port
        // is taken, fall back to an OS-chosen free port (port=0) once; the desktop shell reads the
        // actual port from stdout either way.
        startWithFallback(port);
        // main() returns; the embedded Tomcat's non-daemon threads keep the JVM alive.
    }

    /**
     * Boots Spring Boot on the given port, retrying on {@code --server.port=0} if the requested port
     * cannot be bound (e.g. already in use). {@code port=0} is never retried — the OS always picks a
     * free port, so a failure there is a genuine error.
     */
    private static void startWithFallback(String port) {
        List<String> baseArgs = new ArrayList<>();
        try {
            runSpring(baseArgs, port);
        } catch (RuntimeException e) {
            if ("0".equals(port)) {
                throw e;   // OS-assigned port failed — nothing to fall back to.
            }
            System.err.println("WARN: could not bind port " + port + " (" + e.getMessage()
                + "); retrying on an OS-assigned free port (--server.port=0).");
            runSpring(baseArgs, "0");
        }
    }

    private static void runSpring(List<String> baseArgs, String port) {
        List<String> springArgs = new ArrayList<>(baseArgs);
        springArgs.add("--server.port=" + port);
        new SpringApplicationBuilder(AiApplication.class)
            .run(springArgs.toArray(new String[0]));
    }

    private static void primeLogDirectory() {
        if (System.getProperty("zhiflow.log.dir") != null) return;
        Path logDir = Path.of(System.getProperty("user.dir"), ".zhiflow", "logs");
        try {
            Files.createDirectories(logDir);
        } catch (Exception ignored) {
            // Logback falls back to a relative path; not fatal.
        }
        System.setProperty("zhiflow.log.dir", logDir.toAbsolutePath().toString());
    }
}
