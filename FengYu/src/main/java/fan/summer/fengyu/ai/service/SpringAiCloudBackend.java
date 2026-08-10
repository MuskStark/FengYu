package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.config.ChatModelConfig;
import fan.summer.fengyu.ai.skill.SkillPromptAppender;
import fan.summer.fengyu.ai.skill.SkillRegistry;
import fan.summer.fengyu.ai.tools.ChatToolApprovalGate;
import fan.summer.fengyu.ai.tools.AiPermissionContext;
import fan.summer.fengyu.ai.tools.AiPermissionMode;
import fan.summer.fengyu.ai.tools.ToolResultStatus;
import fan.summer.fengyu.ai.util.JsonHelper;
import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiServiceException;
import fan.summer.fengyu.ai.AiStreamCallback;
import fan.summer.fengyu.ai.AiToolCall;
import fan.summer.fengyu.ai.AiToolResult;
import fan.summer.fengyu.ai.ActiveFilesPromptAppender;
import fan.summer.fengyu.ai.ChatBackend;
import fan.summer.fengyu.ai.ChatFileContext.ActiveFileRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import reactor.core.Disposable;

/**
 * Cloud-mode {@link ChatBackend} backed by Spring AI's {@code OpenAiChatModel} /
 * {@code AnthropicChatModel}. Replaces the LangChain4j {@code CloudChatBackend}.
 *
 * <p>The {@link ChatModel} is built directly from the passed-in endpoint/apiKey/model
 * via {@link fan.summer.fengyu.ai.config.ChatModelConfig#buildOpenAiCompatible} /
 * {@link fan.summer.fengyu.ai.config.ChatModelConfig#buildAnthropic} (NOT from a stale
 * boot-time bean — see {@link #resolveModel}); the provider is fixed at construction
 * time. A {@link ChatClient} is built lazily from the resolved {@link ChatModel}.
 *
 * <p><b>Tool execution (4.0.0 refactor):</b> tool calling now runs on Spring AI's
 * non-deprecated {@link ToolCallingManager} (user-controlled execution). Each
 * {@code chat()} call streams the model response via {@link ChatModel#stream(Prompt)},
 * aggregates the streamed chunks with {@link MessageAggregator}, and — when the model
 * requests tool calls — hands them to {@link ToolCallingManager#executeToolCalls} and
 * re-streams. This fixes a latent bug where tool callbacks were never passed to the
 * Prompt (they are now supplied via {@link ToolCallingChatOptions#getToolCallbacks()}).
 * {@link AiStreamCallback#onToken} / {@code onToolCall} / {@code onToolResult} /
 * {@code onComplete} all still fire so the UI tool-progress contract is preserved.
 */
public final class SpringAiCloudBackend implements ChatBackend {

    private static final Logger log = LoggerFactory.getLogger(SpringAiCloudBackend.class);

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
    private final AtomicBoolean generating = new AtomicBoolean(false);

    /**
     * Cancel handle for the in-flight generation's worker virtual thread. The thread reference
     * is captured when {@code runToolLoop} starts and cleared in its finally; {@link
     * #cancelGeneration()} sets the flag AND interrupts the thread so a worker blocked inside
     * a tool call (e.g. {@code BrowserBridgeClient.invoke}'s HTTP send, which is interruptible
     * per the JDK HttpClient contract) unblocks immediately. The {@code cancelled} flag is the
     * authoritative signal: even if the tool swallows the interrupt (BrowserTool.bridge catches
     * all exceptions into a failure envelope), the round-boundary check in runToolLoop still
     * terminates the loop. Mirrors the AgentRun/AgentRunner pattern (agent path already does
     * this; the ordinary-chat path previously did not, so "stop AI" left in-flight tools running).
     */
    private volatile boolean cancelled = false;
    private volatile Thread workerThread;

    /**
     * The active Spring AI stream subscription for the in-progress generation, plus a latch
     * the worker virtual thread awaits. {@link #cancelGeneration()} disposes the subscription,
     * which terminates the stream and releases the latch so the worker can exit and clear
     * {@link #generating}. Without this a hung upstream (e.g. provider connection stalled)
     * would leave generating=true forever, wedging all subsequent requests.
     */
    private volatile Disposable activeStream;
    private volatile CountDownLatch streamDone;

    /** Cached ChatClient built from {@link #chatModel} (built lazily; null when model is null). */
    private volatile ChatClient chatClient;

    /**
     * The {@link ToolCallback}s made available to the model. Injected by the host wiring
     * (Task 13 registers the first {@code @Tool} bean) or by tests; until then the list is
     * empty and the model simply never requests a tool. Tolerates {@code null} (treated as
     * empty). Replaces the old global tool-registry discovery path.
     */
    private volatile List<ToolCallback> toolCallbacks = List.of();
    private volatile Supplier<List<ToolCallback>> toolCallbackSupplier = () -> toolCallbacks;
    private volatile ChatToolApprovalGate toolApprovalGate;

    /** Shared {@link ToolCallingManager} that drives user-controlled tool execution. */
    private volatile ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

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
        if (chatModel != null) {
            this.chatClient = ChatClient.builder(chatModel).build();
        }
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
        this.toolApprovalGate = toolApprovalGate;
    }

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
    @Override public boolean isGenerating() { return generating.get(); }

    @Override
    public void chat(List<AiChatMessage> history, AiStreamCallback callback) throws AiServiceException {
        chat(history, AiConfigServiceHeadless.getAiTemperature(), AiConfigServiceHeadless.getAiTopP(),
             AiConfigServiceHeadless.getAiMaxTokens(), callback);
    }

    @Override
    public void chat(List<AiChatMessage> history, float temperature, float topP, int maxTokens,
                     List<ActiveFileRef> activeFileRefs, AiStreamCallback callback) throws AiServiceException {
        startChat(history, activeFileRefs, callback, true);
    }

    @Override
    public void chatWithoutTools(List<AiChatMessage> history, AiStreamCallback callback)
            throws AiServiceException {
        startChat(history, List.of(), callback, false);
    }

    private void startChat(List<AiChatMessage> history, List<ActiveFileRef> activeFileRefs,
                           AiStreamCallback callback, boolean enableTools) throws AiServiceException {
        if (!isReady()) throw new AiServiceException(provider + " cloud backend not configured");
        if (!generating.compareAndSet(false, true)) throw new AiServiceException("Generation already in progress");
        AiPermissionMode permissionMode = AiPermissionContext.current();
        // Snapshot the loop cap once per turn so a mid-flight setting change can't extend it.
        int maxToolRounds = fan.summer.fengyu.ai.AiConfigService.getAiMaxToolRounds();

        Thread.ofVirtual().start(() -> {
            AiPermissionContext.set(permissionMode);
            try {
                workerThread = Thread.currentThread();
                runToolLoop(history, activeFileRefs, callback, enableTools, maxToolRounds);
            } catch (Exception e) {
                log.error("{} chat failed", provider, e);
                callback.onError(e);
            } finally {
                workerThread = null;
                // Clear any interrupt raised by cancelGeneration() so it does not leak into a
                // subsequent reuse of this pooled virtual thread.
                Thread.interrupted();
                disposeActiveStream();
                generating.set(false);
                AiPermissionContext.clear();
            }
        });
    }

    @Override public void cancelGeneration() {
        // Dispose the active stream subscription. This terminates the Reactor Flux upstream,
        // which releases the worker's streamDone latch so runToolLoop unblocks and the finally
        // in startChat clears `generating`. Without this a hung upstream (e.g. provider
        // connection stalled) would leave generating=true forever, wedging all subsequent requests.
        disposeActiveStream();
        if (toolApprovalGate != null) toolApprovalGate.cancelPending();
        // Stop in-flight tool calls too (not just the LLM stream): set the flag the loop checks
        // at each round boundary and interrupt the worker so a blocking call inside a tool
        // (e.g. BrowserBridgeClient.invoke's HTTP send) unblocks immediately.
        cancelled = true;
        Thread worker = workerThread;
        if (worker != null) worker.interrupt();
        log.debug("cancelGeneration() requested; active stream disposed");
    }

    /** Dispose the in-flight stream subscription if any; safe to call when idle. */
    private void disposeActiveStream() {
        Disposable d = activeStream;
        if (d != null && !d.isDisposed()) {
            d.dispose();
        }
        CountDownLatch done = streamDone;
        if (done != null) {
            while (done.getCount() > 0) done.countDown();
        }
    }

    // ── Tool loop (Spring AI ToolCallingManager, user-controlled) ──────

    private void runToolLoop(List<AiChatMessage> history, List<ActiveFileRef> activeFileRefs,
                             AiStreamCallback callback, boolean enableTools, int maxToolRounds)
            throws AiServiceException {
        // Re-arm for a fresh turn: cancelGeneration() flips `cancelled` to terminate the previous
        // run; a singleton backend reuses this instance, so the flag must be cleared here or every
        // subsequent chat would abort immediately. Set false AFTER startChat captured the worker
        // thread, so a cancel that races this reset still has a thread to interrupt.
        cancelled = false;
        // Route A fallback: when the host could not transparently inject a FileRef, the model
        // sees the active files here and picks one. Route B injection flows via ChatFileContext
        // (set by AiController around this call) for the transparent path.
        String systemPrompt = ActiveFilesPromptAppender.append(effectiveSystemPrompt(), activeFileRefs);

        // Attach the configured tool callbacks to the PROMPT. We MUST derive the options
        // from baseOptions (the provider-specific OpenAiChatOptions / AnthropicChatOptions
        // the model was built with) via mutate(), NOT a generic
        // ToolCallingChatOptions.builder(): provider models cast prompt.getOptions() to
        // their own concrete type at request-build time (e.g. OpenAiChatModel.createRequest
        // does `(OpenAiChatOptions) prompt.getOptions()`), and a DefaultToolCallingChatOptions
        // throws ClassCastException. mutate() preserves the concrete type. When no tools are
        // registered we still send baseOptions (carries model + sampling params) so the
        // request is built from the right options type.
        ToolCallingChatOptions options = baseOptions;
        List<ToolCallback> currentTools = enableTools ? List.copyOf(toolCallbackSupplier.get()) : List.of();
        ToolCallback[] callbacks = currentTools.toArray(new ToolCallback[0]);
        if (enableTools && callbacks.length > 0) {
            // Attach the callbacks so ToolCallingManager can resolve them. Prefer mutate()
            // on the provider-specific baseOptions (OpenAiChatOptions / AnthropicChatOptions)
            // so the concrete type the provider model casts to is preserved. When baseOptions
            // is null (no provider options, e.g. a plain ChatModel), fall back to a generic
            // ToolCallingChatOptions so the tools are still offered instead of silently
            // dropped — mirroring OllamaLocalBackend. In production baseOptions is never null
            // when chat runs (chatModel and baseOptions are resolved together), so the
            // fallback only affects models that don't require provider-specific options.
            options = baseOptions != null
                    ? baseOptions.mutate().toolCallbacks(callbacks).build()
                    : ToolCallingChatOptions.builder().toolCallbacks(callbacks).build();
        }

        // The Spring AI conversation is the source of truth sent to the model. It starts
        // from FengYu history; once tool calls happen, ToolCallingManager extends it
        // (assistant tool-call msg + ToolResponseMessage) and we carry that forward.
        List<Message> conversation = buildSpringAiMessages(history, systemPrompt);
        // maxToolRounds bounds the number of tool-call rounds; 0 disables the safety net.
        // A loop counter alone cannot bound cost, but it stops a model that re-requests the
        // same tool forever from wedging this virtual thread and locking `generating`.
        for (int round = 0; maxToolRounds <= 0 || round < maxToolRounds; round++) {
            // Authoritative cancel gate: a tool may swallow the interrupt into a failure envelope
            // (BrowserTool.bridge catches all exceptions), so without this check the loop would
            // re-prompt the model with that failure and keep going. Checked at the top of every
            // round — the tightest boundary Spring AI's ToolCallingManager exposes to us.
            if (cancelled) throw new AiServiceException("cancelled");
            Prompt prompt = options != null ? new Prompt(conversation, options) : new Prompt(conversation);

            // Stream this round; fire onToken per token delta; the aggregator hands us the
            // fully-assembled ChatResponse (including any tool calls) on completion.
            StringBuilder accumulated = new StringBuilder();
            AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
            Throwable streamError = streamAndCollect(prompt, accumulated, aggregated, callback);
            if (streamError != null) throw new AiServiceException(provider + " stream failed", streamError);

            ChatResponse roundResp = aggregated.get();
            boolean hasToolCalls = roundResp != null && roundResp.hasToolCalls();

            if (!hasToolCalls) {
                String finalText = accumulated.toString();
                if (!finalText.isBlank()) history.add(AiChatMessage.assistant(finalText));
                int tokens = Math.max(1, finalText.length() / 4);
                callback.onComplete(finalText, tokens, 0);
                return;
            }
            // User-controlled tool execution: let Spring AI's ToolCallingManager run the
            // requested tools (it resolves them against the options' toolCallbacks), firing
            // onToolCall/onToolResult for each so the UI shows tool progress.
            AssistantMessage assistantMsg = roundResp.getResult().getOutput();
            history.add(AiChatMessage.assistantWithTools(accumulated.toString(), mapToolCalls(assistantMsg)));

            if (toolApprovalGate != null) {
                toolApprovalGate.awaitRequiredApprovals(assistantMsg, currentTools, callback);
            }
            fireToolCalls(assistantMsg, callback);
            ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, roundResp);
            fireToolEvents(assistantMsg, result, callback);

            // Carry the manager's extended conversation (original msgs + assistant tool-call
            // msg + ToolResponseMessage) into the next round, and mirror tool results into
            // FengYu's own history for UI parity.
            conversation = result.conversationHistory();
            mirrorToolResultsToHistory(result.conversationHistory(), history, assistantMsg);
        }
        // Loop exhausted its budget without producing a tool-free answer.
        String warn = "Reached maxToolRounds (" + maxToolRounds + ") without a final answer";
        log.warn(warn);
        callback.onError(new IllegalStateException(warn));
    }

    /**
     * Stream a prompt, fire onToken per token delta, capture the aggregated response, and
     * block the calling (virtual) thread until the stream completes or is cancelled. The
     * subscription {@link Disposable} is stored in {@link #activeStream} so
     * {@link #cancelGeneration()} can dispose it mid-stream; {@link #streamDone} is counted
     * down on terminal signals (complete/error/cancel) to release the await below.
     */
    private Throwable streamAndCollect(Prompt prompt, StringBuilder accumulated,
                                       AtomicReference<ChatResponse> aggregated, AiStreamCallback callback) {
        streamDone = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        activeStream = new MessageAggregator().aggregate(
                chatModel.stream(prompt),
                aggregated::set
        ).doOnNext(resp -> {
            if (resp == null || resp.getResult() == null) return;
            AssistantMessage am = resp.getResult().getOutput();
            if (am == null) return;
            String delta = am.getText();
            if (delta != null && !delta.isEmpty()) {
                accumulated.append(delta);
                callback.onToken(delta);
            }
        }).subscribe(
                // onNext consumer — empty: doOnNext above already handled each element
                ignored -> { },
                // onError: stream failed
                error -> { failure.set(error); log.warn("{} stream error", provider, error); streamDone.countDown(); },
                // onComplete (normal finish): release the await. Dispose/cancel is covered by
                // the explicit countDown() in disposeActiveStream().
                streamDone::countDown
        );
        try {
            streamDone.await();   // virtual thread, blocking is fine
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            disposeActiveStream();
            failure.compareAndSet(null, e);
        }
        return failure.get();
    }

    private List<Message> buildSpringAiMessages(List<AiChatMessage> history, String systemPrompt) {
        List<Message> msgs = new ArrayList<>(history.size() + 1);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            msgs.add(new SystemMessage(systemPrompt));
        }
        for (AiChatMessage m : history) msgs.add(AiMessageBridge.toSpringAi(m));
        return msgs;
    }

    private static List<AiToolCall> mapToolCalls(AssistantMessage am) {
        if (am == null || !am.hasToolCalls()) return List.of();
        List<AiToolCall> out = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
            String id = tc.id() != null && !tc.id().isEmpty() ? tc.id() : "tc_" + System.currentTimeMillis();
            out.add(AiToolCall.of(id, tc.name(), parseArgs(tc.arguments())));
        }
        return out;
    }

    private static Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return JsonHelper.parseObject(json); }
        catch (Exception e) { return Map.of(); }
    }

    /**
     * Fire {@code onToolCall}/{@code onToolResult} for each requested tool call, mapping
     * the Spring AI {@link ToolResponseMessage} results back to FengYu's
     * {@link AiToolResult}.
     */
    private static void fireToolEvents(AssistantMessage assistantMsg, ToolExecutionResult result,
                                       AiStreamCallback callback) {
        ToolResponseMessage trm = lastToolResponseMessage(result.conversationHistory());
        if (trm == null || assistantMsg == null || !assistantMsg.hasToolCalls()) return;
        // The ToolResponseMessage responses line up by index with the assistant's tool calls.
        List<AssistantMessage.ToolCall> calls = assistantMsg.getToolCalls();
        List<ToolResponseMessage.ToolResponse> responses = trm.getResponses();
        int n = Math.min(calls.size(), responses.size());
        for (int i = 0; i < n; i++) {
            AssistantMessage.ToolCall tc = calls.get(i);
            ToolResponseMessage.ToolResponse tr = responses.get(i);
            callback.onToolResult(tr.id(), ToolResultStatus.toAiResult(tr.responseData()));
        }
    }

    private static void fireToolCalls(AssistantMessage message, AiStreamCallback callback) {
        if (message == null || !message.hasToolCalls()) return;
        for (AssistantMessage.ToolCall call : message.getToolCalls()) {
            callback.onToolCall(AiToolCall.of(call.id(), call.name(), parseArgs(call.arguments())));
        }
    }

    private static ToolResponseMessage lastToolResponseMessage(List<Message> messages) {
        ToolResponseMessage found = null;
        for (Message m : messages) {
            if (m instanceof ToolResponseMessage trm) found = trm;
        }
        return found;
    }

    /**
     * Mirror the tool-result messages Spring AI added (so the model sees them) back into
     * FengYu's own history list — preserves the [user, assistantWithTools, toolResult,
     * assistant-final] shape the old loop produced. Best-effort; the Spring AI
     * conversation history is the source of truth sent to the model.
     */
    private static void mirrorToolResultsToHistory(List<Message> springAiHistory, List<AiChatMessage> fengyuHistory,
                                                   AssistantMessage assistantMsg) {
        ToolResponseMessage trm = lastToolResponseMessage(springAiHistory);
        if (trm == null || assistantMsg == null || !assistantMsg.hasToolCalls()) return;
        List<AssistantMessage.ToolCall> calls = assistantMsg.getToolCalls();
        List<ToolResponseMessage.ToolResponse> responses = trm.getResponses();
        int n = Math.min(calls.size(), responses.size());
        for (int i = 0; i < n; i++) {
            AssistantMessage.ToolCall tc = calls.get(i);
            ToolResponseMessage.ToolResponse tr = responses.get(i);
            fengyuHistory.add(AiChatMessage.toolResult(
                    tc.id() != null && !tc.id().isEmpty() ? tc.id() : tr.id(),
                    tc.name(), tr.responseData()));
        }
    }

    private static String currentSystemPrompt() {
        try { return AiConfigServiceHeadless.getAiSystemPrompt(); }
        catch (Throwable t) { return null; }
    }

    /**
     * The effective system prompt: the user-configured base prompt with the enabled-skills
     * catalog appended (progressive disclosure). When no skills are enabled, or the registry
     * is unset, the base prompt is returned unchanged. Delegates to
     * {@link SkillPromptAppender} so this stays in lock-step with {@code OllamaLocalBackend}.
     */
    private String effectiveSystemPrompt() {
        return SkillPromptAppender.append(currentSystemPrompt(), skillRegistry);
    }

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
