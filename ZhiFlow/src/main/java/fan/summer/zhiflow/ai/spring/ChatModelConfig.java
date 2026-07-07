package fan.summer.zhiflow.ai.spring;

import com.anthropic.client.AnthropicClient;
import com.openai.client.OpenAIClient;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.AnthropicSetup;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * Defines the three Spring AI {@code ChatModel} beans manually (no starter
 * auto-configuration). Each is constructed from {@link AiConfigProperties}.
 *
 * <p><strong>Cloud client construction (Spring AI 2.0.0 GA).</strong> Spring AI
 * 2.0 rewired OpenAI/Anthropic onto the official vendor Java SDKs
 * ({@code com.openai.client.OpenAIClient} / {@code com.anthropic.client.AnthropicClient}).
 * Rather than depend on the vendor {@code *-client-okhttp} artifacts, we use the
 * setup helpers Spring AI ships for exactly this purpose:
 * <ul>
 *   <li>{@link OpenAiSetup#setupSyncClient} — builds an {@code OpenAIClient} over
 *       Spring AI's own {@code SpringAiOpenAiHttpClient} (OkHttp is already a
 *       compile-scope transitive dependency of {@code spring-ai-openai}).</li>
 *   <li>{@link AnthropicSetup#setupSyncClient} — the Anthropic equivalent.</li>
 * </ul>
 * This lets a custom base URL + API key be honoured with no extra dependency.
 *
 * <p>All three beans always exist; the active one is chosen at mode-switch time
 * by name (the {@code ChatBackend} impl asks for {@code "openAiChatModel"} etc.).
 * Verified builder shapes (Spring AI 2.0.0 GA):
 * <ul>
 *   <li>{@code OpenAiChatModel.builder().openAiClient(client).options(OpenAiChatOptions...)}</li>
 *   <li>{@code AnthropicChatModel.builder().anthropicClient(client).options(AnthropicChatOptions...)}</li>
 *   <li>{@code OllamaChatModel.builder().ollamaApi(api).options(OllamaChatOptions...)}</li>
 * </ul>
 */
@Configuration
public class ChatModelConfig {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(120);
    private static final int MAX_RETRIES = 2;

    @Bean(name = "openAiChatModel")
    public ChatModel openAiChatModel(AiConfigProperties cfg) {
        OpenAIClient client = OpenAiSetup.setupSyncClient(
                cfg.openAiEndpoint(),   // baseUrl
                cfg.openAiApiKey(),     // apiKey
                null,                   // credential
                null,                   // azureDeploymentName
                null,                   // azureOpenAiServiceVersion
                null,                   // organizationId
                false,                  // isAzure
                false,                  // isGitHubModels
                cfg.openAiModel(),      // modelName
                HTTP_TIMEOUT,           // timeout
                MAX_RETRIES,            // maxRetries
                null,                   // proxy
                null,                   // customHeaders
                ObservationRegistry.NOOP,
                null,                   // meterRegistry
                List.of()               // httpClientCustomizers
        );
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(cfg.openAiModel())
                .temperature((double) cfg.temperature())
                .topP((double) cfg.topP())
                .maxTokens(cfg.maxTokens())
                .build();
        return OpenAiChatModel.builder()
                .openAiClient(client)
                .options(options)
                .build();
    }

    @Bean(name = "anthropicChatModel")
    public ChatModel anthropicChatModel(AiConfigProperties cfg) {
        AnthropicClient client = AnthropicSetup.setupSyncClient(
                cfg.anthropicEndpoint(),  // baseUrl
                cfg.anthropicApiKey(),    // apiKey
                HTTP_TIMEOUT,             // timeout
                MAX_RETRIES,              // maxRetries
                null,                     // proxy
                null                      // customHeaders
        );
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .model(cfg.anthropicModel())
                .temperature((double) cfg.temperature())
                .topP((double) cfg.topP())
                .maxTokens(cfg.maxTokens())
                .build();
        return AnthropicChatModel.builder()
                .anthropicClient(client)
                .options(options)
                .build();
    }

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
