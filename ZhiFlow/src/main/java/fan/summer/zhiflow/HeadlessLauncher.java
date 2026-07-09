package fan.summer.zhiflow;

import fan.summer.zhiflow.ai.spring.AiApplication;
import fan.summer.zhiflow.api.log.LoggerBinder;
import fan.summer.zhiflow.log.Slf4jPluginLoggerBinder;
import fan.summer.zhiflow.setup.DataSourceConfigService;
import fan.summer.zhiflow.setup.SetupApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 4 headless entry point. Boots ZhiFlow as a loopback Spring Boot web server in one of
 * two modes, determined by the presence of {@code ~/.zhiflow/config/datasource.properties}:
 *
 * <ul>
 *   <li><b>SETUP mode</b> (config missing): boots {@link SetupApplication} — a minimal context
 *       with only the setup wizard endpoints. No DataSource/JPA. After the wizard completes,
 *       the process exits with {@link ExitCodes#SETUP_DONE} so the Tauri supervisor restarts
 *       into APP mode.</li>
 *   <li><b>APP mode</b> (config present): boots {@link AiApplication} with
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

        boolean configured = isDatasourceConfigured();
        startWithFallback(port, configured);
        // main() returns; the embedded Tomcat's non-daemon threads keep the JVM alive.
    }

    /** True if {@code datasource.properties} exists and is loadable. */
    private static boolean isDatasourceConfigured() {
        try {
            return new DataSourceConfigService().load() != null;
        } catch (Exception e) {
            return false;
        }
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
