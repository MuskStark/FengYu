package fan.summer.zhiflow.ai.spring;

import fan.summer.zhiflow.ai.AiConfigService;
import fan.summer.zhiflow.ai.service.SpringAiCloudBackend;
import fan.summer.zhiflow.api.ai.AiServiceProvider;
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
 */
@Component
public class AiBackendInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AiBackendInitializer.class);

    @Override
    public void run(ApplicationArguments args) {
        String mode = AiConfigService.getAiMode();
        log.info("AI backend mode: {}", mode);
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
            default -> log.info("AI backend: local (deferred, initializes on first use)");
        }
    }
}
