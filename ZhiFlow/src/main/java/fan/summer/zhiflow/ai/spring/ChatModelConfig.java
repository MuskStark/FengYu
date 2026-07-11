package fan.summer.zhiflow.ai.spring;

import com.anthropic.client.AnthropicClient;
import com.openai.client.OpenAIClient;
import fan.summer.zhiflow.ai.AiConfigService;
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
import org.springframework.context.annotation.Lazy;

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
 * <p>All three beans are {@code @Lazy}: they are instantiated only when first
 * looked up by name (the {@code ChatBackend} impl asks for {@code "openAiChatModel"}
 * etc. at mode-switch time), never eagerly at context start. This is essential —
 * a cloud bean built eagerly would throw at startup when no API key is configured
 * (e.g. in local mode), blocking the whole app.
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

    // ── Reusable construction (single source of truth) ────────────────────
    // These static builders take the provider values EXPLICITLY so the hot-swap path
    // (SpringAiCloudBackend.openAi/anthropic/deepSeek factories, invoked by
    // BackendReactivator on every PUT /api/ai/config) can build a ChatModel from the
    // FRESH DB config instead of a stale boot-time AiConfigProperties snapshot. The
    // @Lazy @Bean methods below delegate here for backward compatibility (and any
    // future caller that resolves by bean name), but the live chat path must NOT
    // resolve those beans — it must call these statics with current values.

    /**
     * Builds an OpenAI-compatible {@link ChatModel} (used by both OpenAI and DeepSeek,
     * which exposes an OpenAI-compatible Chat Completions API) from explicit values.
     * Reads the live sampling params (temperature/topP/maxTokens) from
     * {@link fan.summer.zhiflow.ai.AiConfigService} so they pick up hot-swapped config.
     */
    public static ChatModel buildOpenAiCompatible(String baseUrl, String apiKey, String modelName) {
        OpenAIClient client = OpenAiSetup.setupSyncClient(
                baseUrl,                 // baseUrl
                apiKey,                  // apiKey
                null,                    // credential
                null,                    // azureDeploymentName
                null,                    // azureOpenAiServiceVersion
                null,                    // organizationId
                false,                   // isAzure
                false,                   // isGitHubModels
                modelName,               // modelName
                HTTP_TIMEOUT,            // timeout
                MAX_RETRIES,             // maxRetries
                null,                    // proxy
                null,                    // customHeaders
                ObservationRegistry.NOOP,
                null,                    // meterRegistry
                List.of()                // httpClientCustomizers
        );
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(modelName)
                .temperature((double) AiConfigService.getAiTemperature())
                .topP((double) AiConfigService.getAiTopP())
                .maxTokens(AiConfigService.getAiMaxTokens())
                .build();
        return OpenAiChatModel.builder()
                .openAiClient(client)
                .options(options)
                .build();
    }

    /**
     * Builds an Anthropic {@link ChatModel} from explicit values. Reads the live
     * sampling params from {@link fan.summer.zhiflow.ai.AiConfigService}.
     */
    public static ChatModel buildAnthropic(String baseUrl, String apiKey, String modelName) {
        AnthropicClient client = AnthropicSetup.setupSyncClient(
                baseUrl,                 // baseUrl
                apiKey,                  // apiKey
                HTTP_TIMEOUT,            // timeout
                MAX_RETRIES,             // maxRetries
                null,                    // proxy
                null                     // customHeaders
        );
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .model(modelName)
                .temperature((double) AiConfigService.getAiTemperature())
                .topP((double) AiConfigService.getAiTopP())
                .maxTokens(AiConfigService.getAiMaxTokens())
                .build();
        return AnthropicChatModel.builder()
                .anthropicClient(client)
                .options(options)
                .build();
    }

    @Lazy
    @Bean(name = "openAiChatModel")
    public ChatModel openAiChatModel(AiConfigProperties cfg) {
        return buildOpenAiCompatible(cfg.openAiEndpoint(), cfg.openAiApiKey(), cfg.openAiModel());
    }

    /**
     * DeepSeek exposes an OpenAI-compatible Chat Completions API, so it reuses the
     * OpenAI client + model path with DeepSeek's own base URL / key / model tag.
     */
    @Lazy
    @Bean(name = "deepSeekChatModel")
    public ChatModel deepSeekChatModel(AiConfigProperties cfg) {
        return buildOpenAiCompatible(cfg.deepSeekEndpoint(), cfg.deepSeekApiKey(), cfg.deepSeekModel());
    }

    @Lazy
    @Bean(name = "anthropicChatModel")
    public ChatModel anthropicChatModel(AiConfigProperties cfg) {
        return buildAnthropic(cfg.anthropicEndpoint(), cfg.anthropicApiKey(), cfg.anthropicModel());
    }

    @Lazy
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
