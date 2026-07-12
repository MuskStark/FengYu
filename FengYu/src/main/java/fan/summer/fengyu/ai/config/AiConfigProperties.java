package fan.summer.fengyu.ai.config;

import fan.summer.fengyu.ai.AiConfigService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Immutable snapshot of the AI settings read from H2 via {@link AiConfigService}.
 *
 * <p>Snapshotted once at context start (a {@code @Bean} method), so the
 * {@code ChatModel} beans see a consistent view. Mode switching (local/openai/
 * anthropic) re-reads via {@link #snapshot()} at switch time rather than caching
 * here, because the user can change settings while the app runs.
 *
 * <p>The underlying keys are unchanged ({@code ai.openai.*}, {@code ai.anthropic.*},
 * {@code ai.local.*}, {@code ai.temperature}, …). Only the reading path is reused.
 */
public record AiConfigProperties(
        String mode,
        String openAiEndpoint,
        String openAiApiKey,
        String openAiModel,
        String anthropicEndpoint,
        String anthropicApiKey,
        String anthropicModel,
        String deepSeekEndpoint,
        String deepSeekApiKey,
        String deepSeekModel,
        float  temperature,
        float  topP,
        int    maxTokens,
        String systemPrompt,
        String ollamaBaseUrl,   // new: defaults to http://localhost:11434
        String ollamaModel      // new: e.g. "qwen3:4b"; repurposes ai.model.path semantics
) {

    /** Read a fresh snapshot from H2. Called at context start and on mode switch. */
    public static AiConfigProperties snapshot() {
        return new AiConfigProperties(
                AiConfigService.getAiMode(),
                AiConfigService.getAiOpenAiEndpoint(),
                AiConfigService.getAiOpenAiApiKey(),
                AiConfigService.getAiOpenAiModel(),
                AiConfigService.getAiAnthropicEndpoint(),
                AiConfigService.getAiAnthropicApiKey(),
                AiConfigService.getAiAnthropicModel(),
                AiConfigService.getAiDeepSeekEndpoint(),
                AiConfigService.getAiDeepSeekApiKey(),
                AiConfigService.getAiDeepSeekModel(),
                AiConfigService.getAiTemperature(),
                AiConfigService.getAiTopP(),
                AiConfigService.getAiMaxTokens(),
                AiConfigService.getAiSystemPrompt(),
                AiConfigService.getAiOllamaBaseUrl(),
                AiConfigService.getAiOllamaModel()
        );
    }

    /** Spring bean: snapshot once at context start for default model construction. */
    @Configuration
    public static class Config {
        @Bean
        public AiConfigProperties aiConfigProperties() {
            return AiConfigProperties.snapshot();
        }
    }
}
