package fan.summer.zhiflow;

import fan.summer.zhiflow.ai.AiConfigService;
import fan.summer.zhiflow.ai.service.SpringAiCloudBackend;
import fan.summer.zhiflow.ai.spring.AiSpringContext;
import fan.summer.zhiflow.api.ai.AiServiceProvider;
import fan.summer.zhiflow.api.log.LoggerBinder;
import fan.summer.zhiflow.database.DatabaseInit;
import fan.summer.zhiflow.log.Slf4jPluginLoggerBinder;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Phase 1 headless entry point — boots ZhiFlow as a loopback Spring Boot web server, no JavaFX.
 *
 * <p>Usage: {@code java -cp ... fan.summer.zhiflow.HeadlessLauncher --port=<n> --token=<t>}
 * <ul>
 *   <li>{@code --port=<n>} — bind port; {@code 0} (default) picks a free port and prints
 *       {@code ZHIFLOW_PORT=<actual>} to stdout for the Tauri sidecar to read.</li>
 *   <li>{@code --token=<t>} — per-launch auth token; when set, every request must carry it as
 *       the {@code X-ZhiFlow-Token} header (or {@code ?token=} for the SSE stream). When blank,
 *       auth is disabled (browser-dev convenience).</li>
 * </ul>
 */
public final class HeadlessLauncher {

    /** System property the {@code TokenAuthFilter} reads. */
    public static final String TOKEN_PROPERTY = "zhiflow.auth.token";

    private HeadlessLauncher() {}

    public static void main(String[] args) {
        primeLogDirectory();

        int port = 0;
        String token = "";
        for (String a : args) {
            if (a.startsWith("--port=")) {
                try { port = Integer.parseInt(a.substring("--port=".length()).trim()); }
                catch (NumberFormatException ignored) { /* keep default 0 */ }
            } else if (a.startsWith("--token=")) {
                token = a.substring("--token=".length()).trim();
            }
        }
        if (token != null && !token.isBlank()) {
            System.setProperty(TOKEN_PROPERTY, token);
        }

        // Plugin logging bridge (same as the JavaFX path).
        LoggerBinder.bind(new Slf4jPluginLoggerBinder());

        // Database must be up before the Spring context (AiConfigService reads H2 for bean config).
        DatabaseInit.init();

        // Boot the web context (embedded Tomcat, loopback). Prints ZHIFLOW_PORT if port==0.
        AiSpringContext.startWeb(port);

        // Initialize the configured AI backend (cloud modes eager; local is lazy).
        initializeAiBackend();

        // Block forever — the JVM stays up serving requests until the sidecar kills it.
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Mirrors {@code ZhiFlowApp.initializeAiBackend()} without the JavaFX dependency. */
    private static void initializeAiBackend() {
        String mode = AiConfigService.getAiMode();
        switch (mode) {
            case "openai" -> AiServiceProvider.switchMode(mode, SpringAiCloudBackend.openAi(
                AiConfigService.getAiOpenAiEndpoint(),
                AiConfigService.getAiOpenAiApiKey(),
                AiConfigService.getAiOpenAiModel()));
            case "anthropic" -> AiServiceProvider.switchMode(mode, SpringAiCloudBackend.anthropic(
                AiConfigService.getAiAnthropicEndpoint(),
                AiConfigService.getAiAnthropicApiKey(),
                AiConfigService.getAiAnthropicModel()));
            case "deepseek" -> AiServiceProvider.switchMode(mode, SpringAiCloudBackend.deepSeek(
                AiConfigService.getAiDeepSeekEndpoint(),
                AiConfigService.getAiDeepSeekApiKey(),
                AiConfigService.getAiDeepSeekModel()));
            default -> { /* local mode: deferred until first use */ }
        }
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
