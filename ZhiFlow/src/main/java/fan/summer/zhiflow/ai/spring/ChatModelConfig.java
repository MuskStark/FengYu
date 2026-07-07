package fan.summer.zhiflow.ai.spring;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Defines the Spring AI {@code ChatModel} beans manually (no starter
 * auto-configuration). Each is constructed from {@link AiConfigProperties}.
 *
 * <p><strong>Phase 1 scope (Ollama-local first):</strong> only the
 * {@code ollamaChatModel} bean is defined here. The cloud beans
 * ({@code openAiChatModel} / {@code anthropicChatModel}) are DEFERRED to the
 * cloud-migration follow-up: Spring AI 2.0.0 GA rewired OpenAI/Anthropic onto
 * the official vendor Java SDKs ({@code com.openai.client.OpenAIClient} /
 * {@code com.anthropic.client.AnthropicClient}) rather than the
 * {@code OpenAiApi}/{@code AnthropicApi} clients the original plan assumed, and
 * the vendor {@code *-client-okhttp} artifacts are not yet on the classpath.
 * See the plan's "GA API corrections" note and Task 8/cloud-follow-up.
 *
 * <p>Verified builder shape (Spring AI 2.0.0 GA):
 * {@code OllamaChatModel.builder().ollamaApi(api).options(OllamaChatOptions...)}.
 */
@Configuration
public class ChatModelConfig {

    @Bean(name = "ollamaChatModel")
    public ChatModel ollamaChatModel(AiConfigProperties cfg) {
        OllamaApi api = OllamaApi.builder()
                .baseUrl(cfg.ollamaBaseUrl())
                .build();
        return OllamaChatModel.builder()
                .ollamaApi(api)
                .options(OllamaChatOptions.builder()
                        .model(cfg.ollamaModel())
                        .temperature((double) cfg.temperature())
                        .topP((double) cfg.topP())
                        .numPredict(cfg.maxTokens())   // Ollama's max-tokens knob
                        .build())
                .build();
    }
}
