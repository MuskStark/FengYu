package fan.summer.zhiflow.ai.spring;

import fan.summer.zhiflow.ai.AiConfigService;
import fan.summer.zhiflow.ai.service.AiModeService;
import fan.summer.zhiflow.ai.service.SpringAiCloudBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

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
 */
@Component
public class AiBackendInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AiBackendInitializer.class);

    private final AiConfigService aiConfigService;
    private final AiModeService aiMode;

    public AiBackendInitializer(AiConfigService aiConfigService, AiModeService aiMode) {
        this.aiConfigService = aiConfigService;
        this.aiMode = aiMode;
    }

    @Override
    public void run(ApplicationArguments args) {
        String mode = aiConfigService.getAiMode();
        log.info("AI backend mode: {}", mode);
        switch (mode) {
            case "openai" -> aiMode.switchMode(mode, SpringAiCloudBackend.openAi(
                aiConfigService.getAiOpenAiEndpoint(),
                aiConfigService.getAiOpenAiApiKey(),
                aiConfigService.getAiOpenAiModel()));
            case "anthropic" -> aiMode.switchMode(mode, SpringAiCloudBackend.anthropic(
                aiConfigService.getAiAnthropicEndpoint(),
                aiConfigService.getAiAnthropicApiKey(),
                aiConfigService.getAiAnthropicModel()));
            case "deepseek" -> aiMode.switchMode(mode, SpringAiCloudBackend.deepSeek(
                aiConfigService.getAiDeepSeekEndpoint(),
                aiConfigService.getAiDeepSeekApiKey(),
                aiConfigService.getAiDeepSeekModel()));
            default -> log.info("AI backend: local (deferred, initializes on first use)");
        }
    }
}
