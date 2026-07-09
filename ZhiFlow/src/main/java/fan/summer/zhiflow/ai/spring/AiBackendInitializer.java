package fan.summer.zhiflow.ai.spring;

import fan.summer.zhiflow.ai.AiConfigService;
import fan.summer.zhiflow.ai.service.AiModeService;
import fan.summer.zhiflow.ai.service.SpringAiCloudBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Initializes the configured AI backend once the Spring context is up.
 *
 * <p>Cloud modes (openai / anthropic / deepseek) are wired eagerly here; local (Ollama) mode is
 * lazy — deferred until the AI tool is first used. Runs as an {@link ApplicationRunner}, so it
 * executes after context refresh (and after {@link AiContextBridge} has published the context for
 * the backends' imperative {@code ChatModel} lookups).
 *
 * <p>Injects {@link AiConfigService} (now a bean) so the dependency is explicit in the wiring graph.
 * The reads go through the bean's facade; by the time this runner executes (after context refresh),
 * the bean's {@code @PostConstruct} has populated its singleton, so reads are safe.
 *
 * <p><b>Tool wiring (4.0.0 refactor fix — I1):</b> this runner also injects the discovered
 * {@code ToolCallback[]} (the {@code aiToolCallbacks} bean from {@code AiToolDiscoveryConfig}) and
 * applies it to each cloud backend via {@link SpringAiCloudBackend#setToolCallbacks(List)} before
 * handing the backend to {@link AiModeService#switchMode}. Without this, production chat backends
 * carried an empty tool list and the model could never request tools in chat (only the agent path
 * worked, because it injected the bean directly). Ollama is built lazily in its own
 * {@code loadModel}, so it resolves the same bean there.
 */
@Component
public class AiBackendInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AiBackendInitializer.class);

    private final AiConfigService aiConfigService;
    private final AiModeService aiMode;
    /** Discovered tool callbacks (single source of truth from AiToolDiscoveryConfig); applied to each backend. */
    private final ToolCallback[] toolCallbacks;

    public AiBackendInitializer(AiConfigService aiConfigService, AiModeService aiMode,
                                ToolCallback[] toolCallbacks) {
        this.aiConfigService = aiConfigService;
        this.aiMode = aiMode;
        this.toolCallbacks = toolCallbacks != null ? toolCallbacks : new ToolCallback[0];
    }

    @Override
    public void run(ApplicationArguments args) {
        String mode = aiConfigService.getAiMode();
        log.info("AI backend mode: {}", mode);
        switch (mode) {
            case "openai" -> activate(SpringAiCloudBackend.openAi(
                aiConfigService.getAiOpenAiEndpoint(),
                aiConfigService.getAiOpenAiApiKey(),
                aiConfigService.getAiOpenAiModel()), mode);
            case "anthropic" -> activate(SpringAiCloudBackend.anthropic(
                aiConfigService.getAiAnthropicEndpoint(),
                aiConfigService.getAiAnthropicApiKey(),
                aiConfigService.getAiAnthropicModel()), mode);
            case "deepseek" -> activate(SpringAiCloudBackend.deepSeek(
                aiConfigService.getAiDeepSeekEndpoint(),
                aiConfigService.getAiDeepSeekApiKey(),
                aiConfigService.getAiDeepSeekModel()), mode);
            default -> log.info("AI backend: local (deferred, initializes on first use)");
        }
    }

    /**
     * Wire the discovered tool callbacks into the freshly built cloud backend, then hand it to the
     * mode service. Building the backend into a local var first lets us inject tools before
     * {@code switchMode} stores it.
     */
    private void activate(SpringAiCloudBackend backend, String mode) {
        backend.setToolCallbacks(Arrays.asList(toolCallbacks));
        log.info("Wired {} tool callback(s) into {} backend",
                 toolCallbacks.length, backend.provider());
        aiMode.switchMode(mode, backend);
    }
}
