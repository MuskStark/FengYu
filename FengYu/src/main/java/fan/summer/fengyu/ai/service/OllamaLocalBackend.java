package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiConfigService;
import fan.summer.fengyu.ai.AiServiceException;
import fan.summer.fengyu.ai.AiStreamCallback;
import fan.summer.fengyu.ai.ChatBackend;
import fan.summer.fengyu.ai.ChatFileContext.ActiveFileRef;
import fan.summer.fengyu.ai.config.ChatModelConfig;
import fan.summer.fengyu.ai.skill.SkillRegistry;
import fan.summer.fengyu.ai.session.AiRolloutService;
import fan.summer.fengyu.ai.tools.ChatToolApprovalGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Local-mode {@link ChatBackend} backed by Ollama via Spring AI's
 * {@code OllamaChatModel}. Replaces the entire custom GGUF/JNI/worker stack.
 *
 * <p><b>Transport, not orchestrator:</b> the turn loop lives once in
 * {@link ToolLoopDriver}; this class supplies the Ollama-specific half — the tag-served
 * {@link ChatModel}, the delta-reasoning fragment semantics, and the lowercase
 * {@code "ollama"} rollout provider label. The multimodal strict-gateway fallback stays
 * disabled here (Transport defaults).</p>
 *
 * <p>The model is served by an external {@code ollama serve} process; this class
 * only talks to its HTTP API (through Spring AI). "Loading a model" is now
 * selecting an Ollama tag ({@code qwen3:4b}); there is no in-process weight file.</p>
 *
 * <p><b>Thinking (4.1.0):</b> when the selected model is thinking-capable,
 * {@link ChatModelConfig#buildOllama} probes {@code /api/show} and requests thinking
 * ({@code think: true}), and Spring AI surfaces each chunk's reasoning under the
 * {@code AssistantMessage} metadata key {@value #THINKING_METADATA_KEY} — forwarded as
 * {@code onThinking} deltas. Non-capable models keep the think option unset (Ollama
 * rejects {@code think} for them with a 400).</p>
 */
public final class OllamaLocalBackend implements ChatBackend, ToolLoopDriver.Transport {

    private static final Logger log = LoggerFactory.getLogger(OllamaLocalBackend.class);

    /**
     * Spring AI 2.0's {@code OllamaChatModel} puts each streamed chunk's own thinking
     * fragment (not an accumulation) on the {@code AssistantMessage} metadata under this
     * key — mirrors the private {@code OllamaChatModel.THINKING_METADATA_KEY}.
     */
    private static final String THINKING_METADATA_KEY = "thinking";

    private final ToolLoopDriver driver = new ToolLoopDriver(this);

    private volatile String ollamaModelTag;
    private volatile ChatModel chatModel;

    /**
     * The {@link org.springframework.ai.ollama.api.OllamaChatOptions} the {@link ChatModel}
     * was built from. Tool-carrying per-round options MUST be derived from this via
     * {@code mutate()} (see {@link ToolLoopDriver}) — Spring AI 2.0's OllamaChatModel does
     * not merge runtime prompt options with its defaults and rejects options without a
     * model ("model cannot be null or empty"), and it casts prompt options to its concrete
     * type at request-build time.
     */
    private volatile ToolCallingChatOptions baseOptions;

    /** Tool callbacks made available to the model (host wiring / tests); empty until set. */
    private volatile List<ToolCallback> toolCallbacks = List.of();
    private volatile Supplier<List<ToolCallback>> toolCallbackSupplier = () -> toolCallbacks;
    private volatile ChatToolApprovalGate toolApprovalGate;

    /** Optional server-side rollout recorder; null (tests, unwired contexts) skips recording. */
    private volatile AiRolloutService rolloutService;

    /**
     * The live skill registry, used to append the enabled-skills catalog to the system prompt
     * (progressive disclosure). Injected by the host wiring alongside tool callbacks; may be
     * {@code null} (the prompt then carries no skill catalog — zero behaviour change).
     */
    private volatile SkillRegistry skillRegistry;

    public OllamaLocalBackend() {
        this.ollamaModelTag = AiConfigService.getAiOllamaModel();
        // The ChatModel bean is built from H2 config at context start; look it up lazily.
    }

    // ── ChatBackend lifecycle ────────────────────────────────────────

    @Override
    public void loadModel(Path modelPath) throws AiServiceException {
        // In the Ollama world, "load model" = "select the tag". The path argument
        // is honoured only if the user dropped a model file (we read its name as a
        // tag); otherwise the H2-configured tag wins.
        String configured = AiConfigService.getAiOllamaModel();
        if (configured != null && !configured.isBlank()) {
            this.ollamaModelTag = configured;
        } else if (modelPath != null) {
            this.ollamaModelTag = modelPath.getFileName().toString();
        }
        log.info("Ollama local backend: model tag = {}", ollamaModelTag);

        // Build the ChatModel directly from the live DB config via the shared static builder,
        // mirroring the cloud path (SpringAiCloudBackend.openAi/anthropic/deepSeek). This replaces
        // the old AiSpringContext.getBean("ollamaChatModel", ...) service-locator lookup, so the
        // backend no longer depends on a static Spring-context holder.
        try {
            ChatModelConfig.ResolvedModel resolved = ChatModelConfig.buildOllama(
                    AiConfigService.getAiOllamaBaseUrl(), this.ollamaModelTag);
            this.chatModel = resolved.chatModel();
            this.baseOptions = resolved.options();
        } catch (Exception e) {
            throw new AiServiceException("Failed to build Ollama ChatModel: " + e.getMessage(), e);
        }
        // Tool callbacks are injected by BackendReactivator.activateLocal() via setToolCallbacks(...)
        // before loadModel() runs (the same aiToolCallbacks[] the cloud path gets). No context lookup.
        if (!toolCallbacks.isEmpty()) {
            log.info("Ollama backend has {} tool callback(s) wired", toolCallbacks.size());
        }
        if (!probeReachable(AiConfigService.getAiOllamaBaseUrl())) {
            log.warn("Ollama server not reachable at {} — chat will fail at call time. "
                     + "Run `ollama serve` and `ollama pull {}`.",
                     AiConfigService.getAiOllamaBaseUrl(), ollamaModelTag);
        }
    }

    @Override public void unloadModel() {
        // Nothing to release — the model lives in the Ollama server.
        chatModel = null;
        baseOptions = null;
    }

    @Override public boolean isReady() {
        return chatModel != null && ollamaModelTag != null && !ollamaModelTag.isBlank();
    }

    @Override
    public Optional<String> getModelName() {
        return Optional.ofNullable(ollamaModelTag);
    }

    @Override public long getMemoryUsage() {
        // Ollama owns the weights; the JVM's heap usage is not meaningful here.
        return -1;
    }

    @Override public boolean isNativeAvailable() {
        // There is no JNI surface anymore. Return true if the Ollama server is up —
        // this drives the "degraded banner" the AiChatPlugin shows when false.
        return probeReachable(AiConfigService.getAiOllamaBaseUrl());
    }

    /** Sets the {@link ToolCallback}s available to the model (host wiring / tests). */
    public void setToolCallbacks(List<ToolCallback> toolCallbacks) {
        this.toolCallbacks = toolCallbacks != null ? toolCallbacks : List.of();
        this.toolCallbackSupplier = () -> this.toolCallbacks;
    }

    public void setToolCallbackSupplier(Supplier<List<ToolCallback>> supplier) {
        this.toolCallbackSupplier = supplier != null ? supplier : () -> toolCallbacks;
    }

    public void setToolApprovalGate(ChatToolApprovalGate toolApprovalGate) {
        this.toolApprovalGate = toolApprovalGate;
    }

    public void setRolloutService(AiRolloutService rolloutService) {
        this.rolloutService = rolloutService;
    }

    /** Sets the skill registry used for system-prompt catalog injection (host wiring / tests). */
    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    // ── ToolLoopDriver.Transport (the Ollama-specific half of the loop) ──

    @Override public String providerLabel()       { return "Ollama"; }
    @Override public String rolloutProvider()     { return "ollama"; }
    @Override public String modelLabel()          { return ollamaModelTag; }
    @Override public ChatModel chatModel()        { return chatModel; }
    @Override public ToolCallingChatOptions baseOptions() { return baseOptions; }
    @Override public ReasoningForwarder reasoningForwarder() { return ReasoningForwarder.delta(); }
    @Override public String reasoningMetadataKey() { return THINKING_METADATA_KEY; }
    @Override public Supplier<List<ToolCallback>> toolCallbackSupplier() { return toolCallbackSupplier; }
    @Override public ChatToolApprovalGate approvalGate() { return toolApprovalGate; }
    @Override public AiRolloutService rolloutService() { return rolloutService; }
    @Override public SkillRegistry skillRegistry() { return skillRegistry; }

    @Override public io.micrometer.observation.ObservationRegistry observationRegistry() {
        return ChatModelConfig.currentObservationRegistry();
    }

    /** Applies the clamped round budget — Ollama's max-tokens knob is numPredict. */
    @Override public ToolCallingChatOptions withMaxTokens(ToolCallingChatOptions options, int maxTokens) {
        if (options instanceof org.springframework.ai.ollama.api.OllamaChatOptions ollama) {
            return ollama.mutate().numPredict(maxTokens).build();
        }
        return options;
    }

    // ── Chat ──────────────────────────────────────────────────────────

    @Override
    public void chat(List<AiChatMessage> history, AiStreamCallback callback) throws AiServiceException {
        chat(history, AiConfigServiceHeadless.getAiTemperature(), AiConfigServiceHeadless.getAiTopP(),
             AiConfigServiceHeadless.getAiMaxTokens(), callback);
    }

    @Override
    public void chat(List<AiChatMessage> history, float temperature, float topP, int maxTokens,
                     List<ActiveFileRef> activeFileRefs, AiStreamCallback callback) throws AiServiceException {
        if (!isReady()) throw new AiServiceException("Ollama backend not ready (model=" + ollamaModelTag + ")");
        driver.start(history, activeFileRefs, callback, true);
    }

    @Override
    public void chatWithoutTools(List<AiChatMessage> history, AiStreamCallback callback)
            throws AiServiceException {
        if (!isReady()) throw new AiServiceException("Ollama backend not ready (model=" + ollamaModelTag + ")");
        driver.start(history, List.of(), callback, false);
    }

    @Override public void cancelGeneration() { driver.cancel(); }

    @Override public boolean isGenerating() { return driver.isGenerating(); }

    // ── Connection probe (also used by the connection test) ───────────

    /**
     * Pings {@code {base}/api/tags} to check whether an Ollama server is listening.
     * Public so a unit test can drive a fake server.
     */
    public static boolean probeReachable(String baseUrl) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(stripTrailingSlash(baseUrl) + "/api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
