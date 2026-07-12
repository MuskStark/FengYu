package fan.summer.fengyu.ai.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientAsync;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import fan.summer.fengyu.ai.AiConfigService;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.AnthropicSetup;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
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

    /**
     * A resolved cloud model together with the provider-specific
     * {@link ToolCallingChatOptions} it was built from. The options are returned to the
     * caller because {@code SpringAiCloudBackend}'s tool loop must attach
     * {@code ToolCallback}s to the SAME options type the model expects (e.g. an
     * {@code OpenAiChatModel} casts {@code prompt.getOptions()} to
     * {@code OpenAiChatOptions} at request-build time — a generic
     * {@code DefaultToolCallingChatOptions} throws {@code ClassCastException}). The
     * backend derives a tool-carrying copy via {@link ToolCallingChatOptions#mutate()},
     * which preserves the provider-specific concrete type.
     */
    public record ResolvedModel(ChatModel chatModel, ToolCallingChatOptions options) {}

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
     * {@link fan.summer.fengyu.ai.AiConfigService} so they pick up hot-swapped config.
     *
     * @return a {@link ResolvedModel} carrying the model and the provider-specific
     *         {@link OpenAiChatOptions} (which the tool loop must reuse — see
     *         {@link ResolvedModel}).
     */
    public static ResolvedModel buildOpenAiCompatible(String baseUrl, String apiKey, String modelName) {
        // Spring AI 2.0.0's OpenAiChatModel.Builder.build() builds BOTH a sync and an
        // async client when the corresponding field is null (see OpenAiChatModel.java
        // ~L1373/L1382), pulling baseUrl/apiKey/credential from OpenAiChatOptions — which
        // we leave unset here (we only put model + sampling params). So if we supply only
        // the sync client, build() silently rebuilds an async client with no credentials
        // and throws "At least one credential source must be specified: credential (apiKey),
        // workloadIdentity, or adminApiKey". Fix: build BOTH clients ourselves and hand
        // them to the builder so build() never falls back to the options-derived path.
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
        OpenAIClientAsync asyncClient = OpenAiSetup.setupAsyncClient(
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
        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiClient(client)
                .openAiClientAsync(asyncClient)
                .options(options)
                .build();
        return new ResolvedModel(chatModel, options);
    }

    /**
     * Builds an Anthropic {@link ChatModel} from explicit values. Reads the live
     * sampling params from {@link fan.summer.fengyu.ai.AiConfigService}.
     *
     * @return a {@link ResolvedModel} carrying the model and the provider-specific
     *         {@link AnthropicChatOptions} (which the tool loop must reuse — see
     *         {@link ResolvedModel}).
     */
    public static ResolvedModel buildAnthropic(String baseUrl, String apiKey, String modelName) {
        // Same trap as OpenAI (see buildOpenAiCompatible): AnthropicChatModel's constructor
        // rebuilds whichever client (sync/async) is null from AnthropicChatOptions, which
        // carries no credentials here. Build BOTH and hand them in.
        AnthropicClient client = AnthropicSetup.setupSyncClient(
                baseUrl,                 // baseUrl
                apiKey,                  // apiKey
                HTTP_TIMEOUT,            // timeout
                MAX_RETRIES,             // maxRetries
                null,                    // proxy
                null                     // customHeaders
        );
        AnthropicClientAsync asyncClient = AnthropicSetup.setupAsyncClient(
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
        ChatModel chatModel = AnthropicChatModel.builder()
                .anthropicClient(client)
                .anthropicClientAsync(asyncClient)
                .options(options)
                .build();
        return new ResolvedModel(chatModel, options);
    }

    @Lazy
    @Bean(name = "openAiChatModel")
    public ChatModel openAiChatModel(AiConfigProperties cfg) {
        return buildOpenAiCompatible(cfg.openAiEndpoint(), cfg.openAiApiKey(), cfg.openAiModel()).chatModel();
    }

    /**
     * DeepSeek exposes an OpenAI-compatible Chat Completions API, so it reuses the
     * OpenAI client + model path with DeepSeek's own base URL / key / model tag.
     */
    @Lazy
    @Bean(name = "deepSeekChatModel")
    public ChatModel deepSeekChatModel(AiConfigProperties cfg) {
        return buildOpenAiCompatible(cfg.deepSeekEndpoint(), cfg.deepSeekApiKey(), cfg.deepSeekModel()).chatModel();
    }

    @Lazy
    @Bean(name = "anthropicChatModel")
    public ChatModel anthropicChatModel(AiConfigProperties cfg) {
        return buildAnthropic(cfg.anthropicEndpoint(), cfg.anthropicApiKey(), cfg.anthropicModel()).chatModel();
    }

    /**
     * Builds an Ollama {@link ChatModel} from explicit values. Reads the live sampling
     * params from {@link fan.summer.fengyu.ai.AiConfigService}, mirroring the cloud
     * builders — so {@code OllamaLocalBackend} can construct its model directly from the
     * current DB config instead of resolving a boot-time bean via a static context holder.
     */
    public static ChatModel buildOllama(String baseUrl, String modelName) {
        OllamaApi api = OllamaApi.builder()
                .baseUrl(baseUrl)
                .build();
        return OllamaChatModel.builder()
                .ollamaApi(api)
                .options(OllamaChatOptions.builder()
                        .model(modelName)
                        .temperature((double) AiConfigService.getAiTemperature())
                        .topP((double) AiConfigService.getAiTopP())
                        .numPredict(AiConfigService.getAiMaxTokens())   // Ollama's max-tokens knob
                        .build())
                .build();
    }

    @Lazy
    @Bean(name = "ollamaChatModel")
    public ChatModel ollamaChatModel(AiConfigProperties cfg) {
        return buildOllama(cfg.ollamaBaseUrl(), cfg.ollamaModel());
    }
}
