package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.config.ChatModelConfig;
import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiServiceException;
import fan.summer.fengyu.ai.AiStreamCallback;
import fan.summer.fengyu.ai.ChatBackend;
import fan.summer.fengyu.ai.ChatFileContext.ActiveFileRef;
import fan.summer.fengyu.ai.skill.SkillRegistry;
import fan.summer.fengyu.ai.tools.ChatToolApprovalGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Cloud-mode {@link ChatBackend} backed by Spring AI's {@code OpenAiChatModel} /
 * {@code AnthropicChatModel}. Replaces the LangChain4j {@code CloudChatBackend}.
 *
 * <p><b>Transport, not orchestrator:</b> the turn loop (prompt assembly, dynamic tool
 * loading, approval, effect-grouped batch execution, rollout recording, compaction,
 * cancellation) lives once in {@link ToolLoopDriver}; this class supplies the
 * model-specific half — the resolved {@link ChatModel}, its base options, the
 * accumulated-reasoning fragment semantics, and the strict-gateway multimodal
 * fallback — through {@link ToolLoopDriver.Transport}.</p>
 *
 * <p>The {@link ChatModel} is built directly from the passed-in endpoint/apiKey/model
 * via {@link fan.summer.fengyu.ai.config.ChatModelConfig#buildOpenAiCompatible} /
 * {@link fan.summer.fengyu.ai.config.ChatModelConfig#buildAnthropic} (NOT from a stale
 * boot-time bean — see {@link #resolveModel}); the provider is fixed at construction
 * time. The loop speaks to {@link ChatModel} directly (user-controlled tool
 * execution); no ChatClient is built here.</p>
 */
public final class SpringAiCloudBackend implements ChatBackend, ToolLoopDriver.Transport {

    private static final Logger log = LoggerFactory.getLogger(SpringAiCloudBackend.class);

    /**
     * Spring AI 2.0's {@code OpenAiChatModel} exposes reasoning models' chain-of-thought
     * (GLM/DeepSeek {@code reasoning_content}) on each streamed {@code AssistantMessage}
     * under this metadata key as the running concatenation — mirrors the private
     * {@code OpenAiChatModel.REASONING_CONTENT}. Anthropic is not covered: its streaming
     * path emits only a {@code thinking=TRUE} marker per chunk (the text surfaces on the
     * final response), and thinking is not requested there.
     */
    private static final String REASONING_METADATA_KEY = "reasoningContent";

    public enum Provider { OPENAI, ANTHROPIC, DEEPSEEK }

    private final Provider provider;
    private final String endpoint;
    private final String apiKey;
    private final String modelName;
    private final ChatModel chatModel;          // resolved at construction
    /**
     * The provider-specific {@link ToolCallingChatOptions} (e.g. {@code OpenAiChatOptions})
     * the model was built with. Retained so the tool loop can attach {@code ToolCallback}s
     * via {@link ToolCallingChatOptions#mutate()} while keeping the concrete options type
     * the model expects — provider models cast {@code prompt.getOptions()} to their own
     * type at request-build time, so a generic {@code DefaultToolCallingChatOptions}
     * throws {@code ClassCastException}. Null only when the backend is not yet
     * configured.
     */
    private final ToolCallingChatOptions baseOptions;

    private final ToolLoopDriver driver = new ToolLoopDriver(this);

    /**
     * The {@link ToolCallback}s made available to the model. Injected by the host wiring
     * (Task 13 registers the first {@code @Tool} bean) or by tests; until then the list is
     * empty and the model simply never requests a tool. Tolerates {@code null} (treated as
     * empty). Replaces the old global tool-registry discovery path.
     */
    private volatile List<ToolCallback> toolCallbacks = List.of();
    private volatile Supplier<List<ToolCallback>> toolCallbackSupplier = () -> toolCallbacks;
    private volatile ChatToolApprovalGate gate;

    /**
     * Sticky per-endpoint verdict set after a provider 400 that rejected array-form
     * (multimodal) message content — common with strict OpenAI-compatible gateways whose
     * schema only allows string {@code content}. Once observed, tool screenshots are no
     * longer attached for this backend (they stay in FengYu history for the UI); a backend
     * rebind (provider/endpoint change) builds a fresh instance and re-probes.
     */
    private volatile boolean endpointRejectsMediaContent = false;

    // ── Production constructors (look up the ChatModel bean) ──────────

    public static SpringAiCloudBackend openAi(String endpoint, String apiKey, String modelName) {
        ChatModelConfig.ResolvedModel resolved = resolveModel(Provider.OPENAI, endpoint, apiKey, modelName);
        return new SpringAiCloudBackend(Provider.OPENAI, endpoint, apiKey, modelName, resolved);
    }

    public static SpringAiCloudBackend anthropic(String endpoint, String apiKey, String modelName) {
        ChatModelConfig.ResolvedModel resolved = resolveModel(Provider.ANTHROPIC, endpoint, apiKey, modelName);
        return new SpringAiCloudBackend(Provider.ANTHROPIC, endpoint, apiKey, modelName, resolved);
    }

    /** DeepSeek uses an OpenAI-compatible API; the bean reuses the OpenAI model path. */
    public static SpringAiCloudBackend deepSeek(String endpoint, String apiKey, String modelName) {
        ChatModelConfig.ResolvedModel resolved = resolveModel(Provider.DEEPSEEK, endpoint, apiKey, modelName);
        return new SpringAiCloudBackend(Provider.DEEPSEEK, endpoint, apiKey, modelName, resolved);
    }

    /**
     * Builds the {@link ChatModel} directly from the passed-in values when the provider
     * is fully configured. The vendor SDK client throws immediately if the API key is
     * blank. When not configured we return {@code null}: the backend still registers,
     * {@link #isReady()} returns false, and {@code chat()} throws a clean "not
     * configured" message instead of crashing. The model is built on the next
     * {@link fan.summer.fengyu.ai.service.BackendReactivator#reactivate()} once the
     * user fills in the key.
     *
     * <p><b>Why direct construction, not a bean lookup:</b> the cloud {@code ChatModel}
     * beans in {@link fan.summer.fengyu.ai.config.ChatModelConfig} read an
     * {@link fan.summer.fengyu.ai.config.AiConfigProperties} snapshot taken ONCE at
     * context start. A key saved later via the AI config UI (PUT /api/ai/config →
     * {@code AiConfigService} → DB) would never reach a bean built from that stale
     * snapshot, so hot-swap was broken (the bean was always built with the boot-time
     * blank key → "At least one credential source must be specified"). Building inline
     * from the values {@code BackendReactivator} just read from {@code AiConfigService}
     * makes hot-swap actually work — the freshly-saved key flows straight into the
     * client. {@link fan.summer.fengyu.ai.config.ChatModelConfig#buildOpenAiCompatible}
     * / {@link fan.summer.fengyu.ai.config.ChatModelConfig#buildAnthropic} also read
     * the live sampling params (temperature/topP/maxTokens) so those hot-swap too.
     */
    private static ChatModelConfig.ResolvedModel resolveModel(Provider provider,
                                                              String endpoint, String apiKey, String modelName) {
        if (isBlank(endpoint) || isBlank(apiKey) || isBlank(modelName)) {
            log.info("{} backend not fully configured (missing endpoint/apiKey/model); "
                     + "deferring ChatModel resolution until configured", provider);
            return null;
        }
        try {
            return switch (provider) {
                case OPENAI, DEEPSEEK ->
                    ChatModelConfig.buildOpenAiCompatible(endpoint, apiKey, modelName);
                case ANTHROPIC ->
                    ChatModelConfig.buildAnthropic(endpoint, apiKey, modelName);
            };
        } catch (Exception e) {
            log.warn("Failed to build {} ChatModel", provider, e);
            return null;
        }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    // ── Test constructor (inject ChatModel directly, bypass Spring) ───

    SpringAiCloudBackend(ChatModel chatModel) {
        this(Provider.OPENAI, "test", "test-key", "test-model",
                new ChatModelConfig.ResolvedModel(chatModel, null));
    }

    private SpringAiCloudBackend(Provider provider, String endpoint, String apiKey, String modelName,
                                 ChatModelConfig.ResolvedModel resolved) {
        this.provider = provider;
        this.endpoint = endpoint == null ? "" : (endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint);
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.chatModel = resolved != null ? resolved.chatModel() : null;
        this.baseOptions = resolved != null ? resolved.options() : null;
    }


    // ── Public accessors (preserved for SynchronousChatHelper + Settings UI) ──

    public Provider provider()           { return provider; }
    public String getEndpoint()          { return endpoint; }
    public String getApiKey()            { return apiKey; }
    public String getModelNameInternal() { return modelName; }

    /**
     * Sets the {@link ToolCallback}s available to the model (host wiring + tests). Accepts
     * {@code null} (treated as "no tools"). Defensive copy is intentionally NOT made — the
     * caller is expected to pass an effectively-immutable list.
     */
    public void setToolCallbacks(List<ToolCallback> toolCallbacks) {
        this.toolCallbacks = toolCallbacks != null ? toolCallbacks : List.of();
        this.toolCallbackSupplier = () -> this.toolCallbacks;
    }

    public void setToolCallbackSupplier(Supplier<List<ToolCallback>> supplier) {
        this.toolCallbackSupplier = supplier != null ? supplier : () -> toolCallbacks;
    }

    public void setToolApprovalGate(ChatToolApprovalGate toolApprovalGate) {
        this.gate = toolApprovalGate;
    }

    /** Optional server-side rollout recorder; null (tests, unwired contexts) skips recording. */
    public void setRolloutService(fan.summer.fengyu.ai.session.AiRolloutService rolloutService) {
        this.rolloutService = rolloutService;
    }

    private volatile fan.summer.fengyu.ai.session.AiRolloutService rolloutService;

    /**
     * The live skill registry, used to append the enabled-skills catalog to the system prompt
     * (progressive disclosure). Injected by the host wiring alongside tool callbacks; may be
     * {@code null} (the prompt then carries no skill catalog — zero behaviour change).
     */
    private volatile SkillRegistry skillRegistry;

    /** Sets the skill registry used for system-prompt catalog injection (host wiring / tests). */
    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    // ── ToolLoopDriver.Transport (the model-specific half of the loop) ──

    @Override public String providerLabel()       { return String.valueOf(provider); }
    @Override public String rolloutProvider()     { return String.valueOf(provider); }
    @Override public String modelLabel()          { return modelName; }
    @Override public ChatModel chatModel()        { return chatModel; }
    @Override public ToolCallingChatOptions baseOptions() { return baseOptions; }
    @Override public ReasoningForwarder reasoningForwarder() { return ReasoningForwarder.accumulated(); }
    @Override public String reasoningMetadataKey() { return REASONING_METADATA_KEY; }

    @Override public Supplier<List<ToolCallback>> toolCallbackSupplier() { return toolCallbackSupplier; }

    @Override public ChatToolApprovalGate approvalGate() { return gate; }

    @Override public fan.summer.fengyu.ai.session.AiRolloutService rolloutService() { return rolloutService; }

    @Override public SkillRegistry skillRegistry() { return skillRegistry; }

    @Override public boolean mediaContentRejected() { return endpointRejectsMediaContent; }

    @Override public void markMediaContentRejected() { endpointRejectsMediaContent = true; }

    @Override public boolean supportsMediaFallback() { return true; }

    @Override public io.micrometer.observation.ObservationRegistry observationRegistry() {
        return ChatModelConfig.currentObservationRegistry();
    }

    /** Applies the clamped round budget via the provider-specific options mutate. */
    @Override public ToolCallingChatOptions withMaxTokens(ToolCallingChatOptions options, int maxTokens) {
        if (options instanceof org.springframework.ai.openai.OpenAiChatOptions openAi) {
            return openAi.mutate().maxTokens(maxTokens).build();
        }
        if (options instanceof org.springframework.ai.anthropic.AnthropicChatOptions anthropic) {
            return anthropic.mutate().maxTokens(maxTokens).build();
        }
        return options;
    }

    /**
     * Cloud transports add the official SDKs' own retryable markers on top of the base
     * policy: the OpenAI SDK (also serving OpenAI-compatible endpoints like DeepSeek)
     * classifies 429/5xx as OpenAIRetryableException, the Anthropic SDK as
     * AnthropicRetryableException. Spring AI's classification set never fires on these
     * paths — the official SDKs throw their own exception types.
     */
    @Override public org.springframework.core.retry.RetryPolicy retryPolicy() {
        return org.springframework.core.retry.RetryPolicy.builder()
                .maxRetries(2)
                .includes(com.openai.errors.OpenAIRetryableException.class)
                .includes(com.anthropic.errors.AnthropicRetryableException.class)
                .includes(org.springframework.ai.retry.TransientAiException.class)
                .includes(org.springframework.web.client.ResourceAccessException.class)
                .delay(java.time.Duration.ofMillis(500))
                .multiplier(2)
                .maxDelay(java.time.Duration.ofSeconds(4))
                .build();
    }

    /** Compaction summaries carry the provider options the model expects. */
    /**
     * Compaction summaries get a BOUNDED output budget, not the model's full cap: the
     * call fires exactly when the conversation is at 60%/85% of the window, and a
     * validating provider (input + max_tokens ≤ window) would 400 a cap-sized budget at
     * that moment — silently degrading every summary to the hard-truncation fallback
     * (ZCode caps compaction summaries the same way, ~20k).
     */
    static final int SUMMARY_MAX_OUTPUT_TOKENS = 16_000;

    @Override public Prompt summarizePrompt(List<Message> messages) {
        if (baseOptions == null) return new Prompt(messages);
        Integer baked = baseOptions.getMaxTokens();
        int capped = Math.min(baked == null ? SUMMARY_MAX_OUTPUT_TOKENS : baked,
                SUMMARY_MAX_OUTPUT_TOKENS);
        return new Prompt(messages, withMaxTokens(baseOptions, capped));
    }

    // ── ChatBackend ───────────────────────────────────────────────────

    @Override public void loadModel(Path modelPath) throws AiServiceException {
        throw new AiServiceException("Local model loading not supported for cloud backend");
    }

    @Override public void unloadModel() { /* model bean is reused; nothing to release */ }

    @Override public boolean isReady() {
        return chatModel != null
            && endpoint != null && !endpoint.isBlank()
            && apiKey != null && !apiKey.isBlank()
            && modelName != null && !modelName.isBlank();
    }

    @Override public Optional<String> getModelName() { return Optional.ofNullable(modelName); }
    @Override public long getMemoryUsage() { return -1; }
    @Override public boolean isGenerating() { return driver.isGenerating(); }

    @Override
    public void chat(List<AiChatMessage> history, AiStreamCallback callback) throws AiServiceException {
        chat(history, AiConfigServiceHeadless.getAiTemperature(), AiConfigServiceHeadless.getAiTopP(),
             AiConfigServiceHeadless.getAiMaxTokens(), callback);
    }

    @Override
    public void chat(List<AiChatMessage> history, float temperature, float topP, int maxTokens,
                     List<ActiveFileRef> activeFileRefs, AiStreamCallback callback) throws AiServiceException {
        if (!isReady()) throw new AiServiceException(provider + " cloud backend not configured");
        driver.start(history, activeFileRefs, callback, true);
    }

    @Override
    public void chatWithoutTools(List<AiChatMessage> history, AiStreamCallback callback)
            throws AiServiceException {
        if (!isReady()) throw new AiServiceException(provider + " cloud backend not configured");
        driver.start(history, List.of(), callback, false);
    }

    @Override public void cancelGeneration() { driver.cancel(); }

    // ── testConnection (used by Settings UI) ──────────────────────────
    // Raw HTTP probe, independent of the AI library, so connection issues surface
    // as actionable strings rather than wrapped exceptions. Returns null on success.

    public String testConnection() {
        String mode = switch (provider) {
            case OPENAI -> "openai";
            case DEEPSEEK -> "deepseek";
            case ANTHROPIC -> "anthropic";
        };
        ConnectionTester.TestResult r = ConnectionTester.testCloud(mode, endpoint, apiKey, modelName);
        return r.success() ? null : r.error();
    }
}
