package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.AiConfigService;
import fan.summer.fengyu.ai.config.ChatModelConfig;
import fan.summer.fengyu.ai.skill.SkillPromptAppender;
import fan.summer.fengyu.ai.skill.SkillRegistry;
import fan.summer.fengyu.ai.tools.ChatToolApprovalGate;
import fan.summer.fengyu.ai.util.JsonHelper;
import fan.summer.fengyu.api.ai.AiChatMessage;
import fan.summer.fengyu.api.ai.AiServiceException;
import fan.summer.fengyu.api.ai.AiStreamCallback;
import fan.summer.fengyu.api.ai.AiToolCall;
import fan.summer.fengyu.api.ai.AiToolResult;
import fan.summer.fengyu.api.ai.ChatBackend;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import reactor.core.Disposable;

/**
 * Local-mode {@link ChatBackend} backed by Ollama via Spring AI's
 * {@code OllamaChatModel}. Replaces the entire custom GGUF/JNI/worker stack.
 *
 * <p>The model is served by an external {@code ollama serve} process; this class
 * only talks to its HTTP API (through Spring AI). "Loading a model" is now
 * selecting an Ollama tag ({@code qwen3:4b}); there is no in-process weight file.
 *
 * <p><b>Tool execution (4.0.0 refactor):</b> tool calling now runs on Spring AI's
 * non-deprecated {@link ToolCallingManager} (user-controlled execution), mirroring
 * {@code SpringAiCloudBackend}. {@link AiStreamCallback#onToken} / {@code onToolCall} /
 * {@code onToolResult} / {@code onComplete} all still fire. The old global tool-registry
 * discovery + manual tool-executor loop is gone; tools are injected via
 * {@link #setToolCallbacks(List)}.
 *
 * <p>Phase 1: thinking surfacing is NOT wired (Task 8 spike fallback — Ollama
 * unavailable on the build host to confirm the streaming thinking-metadata key).
 * {@code AiStreamCallback.onThinking} is never invoked here; see the plan's Task 8
 * outcome. TODO: surface thinking once the metadata key is confirmed.
 */
public final class OllamaLocalBackend implements ChatBackend {

    private static final Logger log = LoggerFactory.getLogger(OllamaLocalBackend.class);
    private static final int MAX_TOOL_ROUNDS = 8;

    private final AtomicBoolean generating = new AtomicBoolean(false);

    /**
     * The active Spring AI stream subscription for the in-progress generation, plus a latch
     * the worker virtual thread awaits. {@link #cancelGeneration()} disposes the subscription,
     * which terminates the stream and releases the latch so the worker can exit and clear
     * {@link #generating}. Both are volatile: written by the worker thread, read by the
     * (possibly different) thread that calls {@code cancelGeneration()}.
     */
    private volatile Disposable activeStream;
    private volatile CountDownLatch streamDone;

    private volatile String ollamaModelTag;
    private volatile ChatModel chatModel;

    /** Cached ChatClient built from {@link #chatModel} when the model is loaded. */
    private volatile ChatClient chatClient;

    /** Tool callbacks made available to the model (host wiring / tests); empty until set. */
    private volatile List<ToolCallback> toolCallbacks = List.of();
    private volatile ChatToolApprovalGate toolApprovalGate;

    /** Shared {@link ToolCallingManager} that drives user-controlled tool execution. */
    private volatile ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

    public OllamaLocalBackend() {
        this.ollamaModelTag = AiConfigService.getAiOllamaModel();
        // The ChatModel bean is built from H2 config at context start; look it up lazily.
    }

    // ── ChatBackend lifecycle ────────────────────────────────────────

    @Override
    public void loadModel(Path modelPath) throws AiServiceException {
        // In the Ollama world, "load model" = "select the tag". The path argument
        // is honoured only if the user dropped a model file (we read its name as
        // a tag); otherwise the H2-configured tag wins.
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
            this.chatModel = ChatModelConfig.buildOllama(
                    AiConfigService.getAiOllamaBaseUrl(), this.ollamaModelTag);
            this.chatClient = ChatClient.builder(this.chatModel).build();
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
        chatClient = null;
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

    // ── Chat ──────────────────────────────────────────────────────────

    @Override
    public void chat(List<AiChatMessage> history, AiStreamCallback callback) throws AiServiceException {
        chat(history, AiConfigServiceHeadless.getAiTemperature(), AiConfigServiceHeadless.getAiTopP(),
             AiConfigServiceHeadless.getAiMaxTokens(), callback);
    }

    @Override
    public void chat(List<AiChatMessage> history, float temperature, float topP, int maxTokens,
                     AiStreamCallback callback) throws AiServiceException {
        startChat(history, callback, true);
    }

    @Override
    public void chatWithoutTools(List<AiChatMessage> history, AiStreamCallback callback)
            throws AiServiceException {
        startChat(history, callback, false);
    }

    private void startChat(List<AiChatMessage> history, AiStreamCallback callback,
                           boolean enableTools) throws AiServiceException {
        if (!isReady()) throw new AiServiceException("Ollama backend not ready (model=" + ollamaModelTag + ")");
        if (!generating.compareAndSet(false, true)) throw new AiServiceException("Generation already in progress");

        Thread.ofVirtual().start(() -> {
            try {
                runToolLoop(history, callback, enableTools);
            } catch (Exception e) {
                log.error("Ollama chat failed", e);
                callback.onError(e);
            } finally {
                disposeActiveStream();
                generating.set(false);
            }
        });
    }

    @Override public void cancelGeneration() {
        // Dispose the active stream subscription. This terminates the Reactor Flux upstream,
        // which releases the worker's streamDone latch so runToolLoop unblocks and the finally
        // in startChat clears `generating`. Without this a hung model (e.g. ollama process
        // unresponsive) would leave generating=true forever, wedging all subsequent requests.
        disposeActiveStream();
        if (toolApprovalGate != null) toolApprovalGate.cancelPending();
        log.debug("cancelGeneration() requested; active stream disposed");
    }

    /** Dispose the in-flight stream subscription if any; safe to call when idle. */
    private void disposeActiveStream() {
        Disposable d = activeStream;
        if (d != null && !d.isDisposed()) {
            d.dispose();
        }
        // Release any worker still blocked on the latch (e.g. dispose fired before onComplete).
        CountDownLatch done = streamDone;
        if (done != null) {
            while (done.getCount() > 0) done.countDown();
        }
    }

    @Override public boolean isGenerating() { return generating.get(); }

    // ── Tool loop (Spring AI ToolCallingManager, user-controlled) ──────

    private void runToolLoop(List<AiChatMessage> history, AiStreamCallback callback,
                             boolean enableTools) {
        String systemPrompt = effectiveSystemPrompt();

        // Tool-callback options attached to every Prompt so the model CAN request tools
        // (bug fix: previously buildToolCallbacks()'s result was discarded at the call site).
        ToolCallback[] callbacks = enableTools
                ? this.toolCallbacks.toArray(new ToolCallback[0])
                : new ToolCallback[0];
        ToolCallingChatOptions options = callbacks.length == 0
                ? null
                : ToolCallingChatOptions.builder().toolCallbacks(callbacks).build();

        List<Message> conversation = buildSpringAiMessages(history, systemPrompt);

        for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
            Prompt prompt = options != null ? new Prompt(conversation, options) : new Prompt(conversation);

            // Stream this round; fire onToken per token delta; the aggregator hands us the
            // fully-assembled ChatResponse (including any tool calls) on completion. The stream
            // is subscribed explicitly (not blockLast) so cancelGeneration() can dispose it and
            // unblock this virtual thread via the latch.
            StringBuilder accumulated = new StringBuilder();
            AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
            streamAndCollect(prompt, accumulated, aggregated, callback);

            ChatResponse roundResp = aggregated.get();
            boolean hasToolCalls = roundResp != null && roundResp.hasToolCalls();

            if (!hasToolCalls) {
                String finalText = accumulated.toString();
                if (!finalText.isBlank()) history.add(AiChatMessage.assistant(finalText));
                int tokens = Math.max(1, finalText.length() / 4);
                callback.onComplete(finalText, tokens, 0);
                return;
            }
            if (round == MAX_TOOL_ROUNDS) {
                String warn = "Reached MAX_TOOL_ROUNDS (" + MAX_TOOL_ROUNDS + ")";
                log.warn(warn);
                callback.onComplete(warn, 0, 0);
                return;
            }

            AssistantMessage assistantMsg = roundResp.getResult().getOutput();
            history.add(AiChatMessage.assistantWithTools(accumulated.toString(), mapToolCalls(assistantMsg)));

            if (toolApprovalGate != null) {
                toolApprovalGate.awaitRequiredApprovals(assistantMsg, toolCallbacks, callback);
            }
            ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, roundResp);
            fireToolEvents(assistantMsg, result, callback);

            conversation = result.conversationHistory();
            mirrorToolResultsToHistory(result.conversationHistory(), history, assistantMsg);
        }
    }

    /**
     * Stream a prompt, fire onToken per token delta, capture the aggregated response, and
     * block the calling (virtual) thread until the stream completes or is cancelled. The
     * subscription {@link Disposable} is stored in {@link #activeStream} so
     * {@link #cancelGeneration()} can dispose it mid-stream; {@link #streamDone} is counted
     * down on terminal signals (complete/error/cancel) to release the await below.
     */
    private void streamAndCollect(Prompt prompt, StringBuilder accumulated,
                                  AtomicReference<ChatResponse> aggregated, AiStreamCallback callback) {
        streamDone = new CountDownLatch(1);
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
            // Task 8 fallback: thinking content is NOT surfaced in Phase 1.
        }).subscribe(
                // onNext consumer — empty: doOnNext above already handled each element
                ignored -> { },
                // onError: stream failed
                error -> { log.warn("Ollama stream error", error); streamDone.countDown(); },
                // onComplete (normal finish): release the await. Dispose/cancel is covered by
                // the explicit countDown() in disposeActiveStream().
                streamDone::countDown
        );
        try {
            streamDone.await();   // virtual thread, blocking is fine
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            disposeActiveStream();
        }
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

    private static void fireToolEvents(AssistantMessage assistantMsg, ToolExecutionResult result,
                                       AiStreamCallback callback) {
        ToolResponseMessage trm = lastToolResponseMessage(result.conversationHistory());
        if (trm == null || assistantMsg == null || !assistantMsg.hasToolCalls()) return;
        List<AssistantMessage.ToolCall> calls = assistantMsg.getToolCalls();
        List<ToolResponseMessage.ToolResponse> responses = trm.getResponses();
        int n = Math.min(calls.size(), responses.size());
        for (int i = 0; i < n; i++) {
            AssistantMessage.ToolCall tc = calls.get(i);
            ToolResponseMessage.ToolResponse tr = responses.get(i);
            callback.onToolCall(AiToolCall.of(
                    tc.id() != null && !tc.id().isEmpty() ? tc.id() : tr.id(),
                    tc.name(), parseArgs(tc.arguments())));
            callback.onToolResult(tr.id(), AiToolResult.success(tr.responseData()));
        }
    }

    private static ToolResponseMessage lastToolResponseMessage(List<Message> messages) {
        ToolResponseMessage found = null;
        for (Message m : messages) {
            if (m instanceof ToolResponseMessage trm) found = trm;
        }
        return found;
    }

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
     * {@link SkillPromptAppender} so this stays in lock-step with {@code SpringAiCloudBackend}.
     */
    private String effectiveSystemPrompt() {
        return SkillPromptAppender.append(currentSystemPrompt(), skillRegistry);
    }

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
