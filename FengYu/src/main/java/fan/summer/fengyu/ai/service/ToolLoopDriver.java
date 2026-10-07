package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiConfigService;
import fan.summer.fengyu.ai.AiServiceException;
import fan.summer.fengyu.ai.AiStreamCallback;
import fan.summer.fengyu.ai.AiToolCall;
import fan.summer.fengyu.ai.AiToolResult;
import fan.summer.fengyu.ai.ActiveFilesPromptAppender;
import fan.summer.fengyu.ai.config.ModelMetadataCatalog;
import fan.summer.fengyu.ai.skill.SkillPromptAppender;
import fan.summer.fengyu.ai.skill.SkillRegistry;
import fan.summer.fengyu.ai.session.AiRolloutService;
import fan.summer.fengyu.ai.session.ConversationCompactor;
import fan.summer.fengyu.ai.tools.AiPermissionContext;
import fan.summer.fengyu.ai.tools.AiPermissionMode;
import fan.summer.fengyu.ai.tools.BoundToolsContext;
import fan.summer.fengyu.ai.tools.ChatToolApprovalGate;
import fan.summer.fengyu.ai.tools.ConversationContext;
import fan.summer.fengyu.ai.tools.ToolActivationContext;
import fan.summer.fengyu.ai.tools.ToolApprovalContext;
import fan.summer.fengyu.ai.tools.ToolActivationState;
import fan.summer.fengyu.ai.tools.ToolCatalogPromptAppender;
import fan.summer.fengyu.ai.tools.ToolLoadingPolicy;
import fan.summer.fengyu.ai.tools.ToolResultStatus;
import fan.summer.fengyu.ai.util.JsonHelper;
import fan.summer.fengyu.ai.ChatFileContext.ActiveFileRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import reactor.core.Disposable;

/**
 * The turn-orchestration loop shared by the two {@link fan.summer.fengyu.ai.ChatBackend}
 * transports. What used to be duplicated line-for-line in {@code SpringAiCloudBackend}
 * and {@code OllamaLocalBackend} lives here once: system-prompt assembly (workspace +
 * active files + skills catalog + plan mode), dynamic tool loading, turn-start
 * compaction, the model/tool round loop (cancel gate, approval gate, effect-grouped
 * batch execution via {@link ToolBatchExecutor}, media bridging, history mirroring,
 * rollout recording, mid-turn compaction), and the generation lifecycle (virtual worker
 * thread, stream subscription, cancellation).
 *
 * <p>A backend supplies its model-specific differences through {@link Transport}:
 * provider/model labels, the resolved {@link ChatModel} and its base options, the
 * reasoning-forwarder semantics (accumulated vs delta), and the cloud-only
 * strict-gateway media fallback. Everything else is transport-neutral and exists in
 * exactly one place — the single seam upcoming work (OS-sandbox attempt loop, code-mode
 * nested tool calls) integrates against instead of grafting into each backend.</p>
 */
final class ToolLoopDriver {

    private static final Logger log = LoggerFactory.getLogger(ToolLoopDriver.class);

    /** Hard ceiling when the configured maxToolRounds is 0 ("unlimited"). */
    private static final int HARD_MAX_TOOL_ROUNDS = 200;

    /**
     * The model-specific half of the loop, implemented by each backend. Read at call
     * time (fields stay late-injectable via the backend setters); {@code null} returns
     * for the optional collaborators simply disable that feature, as before.
     */
    interface Transport {

        /** Label for logs and error messages ("OPENAI", "Ollama", ...). */
        String providerLabel();

        /** Provider recorded in the rollout turn_start event (cloud: same as the label). */
        default String rolloutProvider() { return providerLabel(); }

        /** Model name recorded in the rollout turn_start event. */
        String modelLabel();

        /** The resolved model to stream and summarize against. */
        ChatModel chatModel();

        /** The provider-specific base options round options mutate() from; may be null. */
        ToolCallingChatOptions baseOptions();

        /** One forwarder per model stream — accumulated (OpenAI-style) or delta (Ollama). */
        ReasoningForwarder reasoningForwarder();

        /** The AssistantMessage metadata key reasoning fragments arrive under. */
        String reasoningMetadataKey();

        /**
         * The prompt compaction summaries are requested with. The cloud transports derive
         * it from their base options; the default plain prompt matches local mode.
         */
        default Prompt summarizePrompt(List<Message> messages) { return new Prompt(messages); }

        /** The tool callbacks available to the model, read fresh each turn. */
        Supplier<List<ToolCallback>> toolCallbackSupplier();

        /** The approval gate; null disables approval (tests, unwired contexts). */
        default ChatToolApprovalGate approvalGate() { return null; }

        /** The rollout recorder source; null skips server-side recording. */
        default AiRolloutService rolloutService() { return null; }

        /** The live skill registry; null omits the skills catalog from the prompt. */
        default SkillRegistry skillRegistry() { return null; }

        /**
         * Cloud-only strict-gateway fallback: sticky per-backend verdict that the
         * endpoint rejects multimodal (array-form) content. The defaults (never
         * rejected, mark is a no-op, no fallback) keep local-mode behavior untouched —
         * the retry below additionally requires {@link #supportsMediaFallback()} so an
         * Ollama error string that happens to match the heuristic cannot flip behavior.
         */
        default boolean mediaContentRejected() { return false; }

        default void markMediaContentRejected() { }

        /** Whether this endpoint gets the media-strip retry at all (cloud gateways only). */
        default boolean supportsMediaFallback() { return false; }

        /**
         * Observation registry for tool-call instrumentation (Spring AI's
         * {@code ToolCallingManager} emits a tool observation per executed call). NOOP
         * until the managed registry is bridged in; models get theirs from
         * {@code ChatModelConfig}'s holder.
         */
        default io.micrometer.observation.ObservationRegistry observationRegistry() {
            return io.micrometer.observation.ObservationRegistry.NOOP;
        }

        /**
         * Applies one round's clamped output budget to the options. Each transport
         * implements it with its CONCRETE options type (the field is maxTokens everywhere
         * except Ollama's numPredict — a generic mutate cannot know that); returning the
         * options unchanged keeps the baseline when a transport opts out.
         */
        default ToolCallingChatOptions withMaxTokens(ToolCallingChatOptions options, int maxTokens) {
            return options;
        }

        /**
         * Transient-error classification for model calls, as a Spring Core
         * {@link RetryPolicy} — the same abstraction Spring AI's own {@code RetryUtils}
         * builds its templates on. The default covers Spring AI's classification
         * ({@code TransientAiException}, thrown by RestClient-based providers) and
         * network IO ({@code ResourceAccessException}, the Ollama path); official-SDK
         * transports override to add their SDK's retryable marker types.
         */
        default RetryPolicy retryPolicy() { return baseRetryPolicy(); }
    }

    private final Transport transport;

    /**
     * Per-turn snapshots for the round assembler: the resolved context window and output
     * budget of THIS turn's model. Written once at the top of runToolLoop (the
     * single-generation CAS in start() guarantees at most one live turn per driver), read
     * by roundOptions each round.
     */
    private int contextWindowTokens;
    private int modelMaxOutputTokens;

    private final AtomicBoolean generating = new AtomicBoolean(false);

    /**
     * Cancel handle for the in-flight generation's worker virtual thread. {@link
     * #cancel()} sets the flag AND interrupts the thread so a worker blocked inside a
     * tool call (e.g. {@code BrowserBridgeClient.invoke}'s HTTP send, which is
     * interruptible per the JDK HttpClient contract) unblocks immediately. The
     * {@code cancelled} flag is the authoritative signal: even if the tool swallows the
     * interrupt (BrowserTool.bridge catches all exceptions into a failure envelope),
     * the round-boundary check in runToolLoop still terminates the loop.
     */
    private volatile boolean cancelled = false;
    private volatile Thread workerThread;
    /**
     * The conversation of the in-flight generation, captured on the caller thread
     * (where the controller has it bound) so {@link #cancel()} — which runs on a
     * different thread — can terminate THIS turn's active code-mode cells.
     */
    private volatile Long conversationId;

    /**
     * The active Spring AI stream subscription for the in-progress generation, plus a
     * latch the worker virtual thread awaits. {@link #cancel()} disposes the
     * subscription, which terminates the stream and releases the latch so the worker
     * can exit and clear {@link #generating}.
     */
    private volatile Disposable activeStream;
    private volatile CountDownLatch streamDone;

    /**
     * Shared {@link ToolCallingManager} that drives user-controlled tool execution.
     * Spring AI's per-turn tool-call limits, made explicit instead of implicit: the
     * defaults are 40 calls per tool / 150 per turn, and the default breach behavior
     * is THROW — which would kill the whole turn on a runaway tool. We keep the
     * default values but switch to {@link ToolCallLimitBehavior#RETURN_ERROR_RESPONSE}:
     * the breach becomes a synthesized error tool result the model can react to, the
     * same "a refusal never ends the turn" contract the approval gate follows.
     */
    private final ToolCallingManager toolCallingManager;

    /** Retry engine built from the transport's policy; see {@link Transport#retryPolicy()}. */
    private final RetryTemplate retryTemplate;

    /** The metadata key Spring AI's OpenAI module maps to the reasoning_content wire field. */
    static final String REASONING_METADATA_KEY = "reasoningContent";

    /**
     * Synthetic ids for blank-id providers' tool calls. Same-millis collisions would
     * cross-match two calls (the approval gate's BLANK_ID_SEQ rationale) — a monotonic
     * counter instead of a timestamp.
     */
    private static final java.util.concurrent.atomic.AtomicLong SYNTHETIC_ID_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * The base transient-error policy: Spring AI's own classification plus network IO,
     * with an interactive-turn backoff. Deliberately NOT Spring AI's
     * {@code RetryUtils.DEFAULT_RETRY_TEMPLATE} (10 retries, up to 3 minutes apart) —
     * a chat turn must fail visibly long before that; nor its classification alone,
     * which the official OpenAI/Anthropic SDK paths never throw.
     */
    static RetryPolicy baseRetryPolicy() {
        return RetryPolicy.builder()
                .maxRetries(2)
                .includes(TransientAiException.class)
                .includes(ResourceAccessException.class)
                .delay(Duration.ofMillis(500))
                .multiplier(2)
                .maxDelay(Duration.ofSeconds(4))
                .build();
    }

    ToolLoopDriver(Transport transport) {
        this.transport = transport;
        this.toolCallingManager = ToolCallingManager.builder()
                .observationRegistry(transport.observationRegistry())
                .maxCallsPerTool(DefaultToolCallingManager.DEFAULT_MAX_CALLS_PER_TOOL)
                .maxTotalToolCalls(DefaultToolCallingManager.DEFAULT_MAX_TOTAL_TOOL_CALLS)
                .onLimitExceeded(ToolCallLimitBehavior.RETURN_ERROR_RESPONSE)
                .build();
        this.retryTemplate = new RetryTemplate(transport.retryPolicy());
    }

    boolean isGenerating() {
        return generating.get();
    }

    /**
     * Starts one turn on a fresh virtual worker thread. The caller (the backend's
     * {@code chat} methods) has already verified readiness; this is the CAS + spawn
     * that used to open each backend's {@code startChat}.
     */
    void start(List<AiChatMessage> history, List<ActiveFileRef> activeFileRefs,
               AiStreamCallback callback, boolean enableTools) throws AiServiceException {
        if (!generating.compareAndSet(false, true)) throw new AiServiceException("Generation already in progress");
        // Re-arm the cancel flag on the CALLER thread, before the worker exists: a cancellation
        // landing in the gap between the CAS and the worker's first line would otherwise be
        // overwritten by a late `cancelled = false` and silently lost.
        cancelled = false;
        conversationId = ConversationContext.current();
        AiPermissionMode permissionMode = AiPermissionContext.current();
        // Snapshot the loop cap once per turn so a mid-flight setting change can't extend it.
        int maxToolRounds = AiConfigService.getAiMaxToolRounds();

        Thread.ofVirtual().start(() -> {
            AiPermissionContext.set(permissionMode);
            try {
                workerThread = Thread.currentThread();
                runToolLoop(history, activeFileRefs, callback, enableTools, maxToolRounds);
            } catch (Exception e) {
                if (e instanceof ChatToolApprovalGate.ToolApprovalException) {
                    // Rejection, timeout, and cancellation are expected user-controlled outcomes,
                    // not backend crashes. Preserve the concise message without an alarming stack.
                    log.info("{} chat stopped at tool approval: {}", transport.providerLabel(), e.getMessage());
                } else {
                    log.error("{} chat failed", transport.providerLabel(), e);
                }
                // Clear `generating` BEFORE invoking onError. A caller that awaits the error
                // callback (e.g. a test, or the UI starting a retry) observes the backend as
                // reusable the moment onError fires; if the flag stayed set until the finally
                // block, a racing next chat() would hit "Generation already in progress" even
                // though this turn had already failed. Dispose + clear here, keep the finally
                // clear as a belt-and-braces fallback (idempotent set).
                workerThread = null;
                Thread.interrupted();
                disposeActiveStream();
                generating.set(false);
                callback.onError(e);
            } finally {
                workerThread = null;
                // Clear any interrupt raised by cancel() so it does not leak into a
                // subsequent reuse of this pooled virtual thread.
                Thread.interrupted();
                disposeActiveStream();
                generating.set(false);
                AiPermissionContext.clear();
            }
        });
    }

    /** Cancels the in-flight generation: stream disposed, pending approvals dropped, worker interrupted. */
    void cancel() {
        // Dispose the active stream subscription. This terminates the Reactor Flux upstream,
        // which releases the worker's streamDone latch so runToolLoop unblocks and the finally
        // in start clears `generating`. Without this a hung upstream would leave
        // generating=true forever, wedging all subsequent requests.
        disposeActiveStream();
        ChatToolApprovalGate gate = transport.approvalGate();
        if (gate != null) gate.cancelPending();
        // A cancelled turn must not leak still-running code-mode cells to their natural
        // end (codex interrupt_active_cells): Terminate command + forced context close.
        // Only for a BOUND conversation: with a null id the ad-hoc "flow" namespace is
        // shared by every unbound flow, and removing it would kill cells belonging to
        // unrelated flows that happen to be unbound too.
        if (conversationId != null) {
            fan.summer.fengyu.ai.codemode.CodeModeExecTool.terminateActiveCellsFor(conversationId);
        }
        // Stop in-flight tool calls too (not just the LLM stream): set the flag the loop checks
        // at each round boundary and interrupt the worker so a blocking call inside a tool
        // (e.g. BrowserBridgeClient.invoke's HTTP send) unblocks immediately.
        cancelled = true;
        Thread worker = workerThread;
        if (worker != null) worker.interrupt();
        log.debug("cancel requested; active stream disposed");
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

    // ── the loop ─────────────────────────────────────────────────────────────────────────

    private void runToolLoop(List<AiChatMessage> historyIn, List<ActiveFileRef> activeFileRefs,
                             AiStreamCallback callback, boolean enableTools, int maxToolRounds)
            throws AiServiceException {
        // The loop APPENDS to the history (the controller's live list — its mutations
        // persist). Callers passing an immutable snapshot (subagent runners once did,
        // List.of(...)) crashed the turn with UnsupportedOperationException, so a non-
        // mutable list is defensively copied here: the turn survives either way.
        List<AiChatMessage> history = historyIn instanceof ArrayList<?>
                ? historyIn : new ArrayList<>(historyIn);
        // One-shot snapshot of the context window for the whole turn: an explicit user
        // setting wins, otherwise the model-metadata catalog supplies the model's real
        // window (a 128k-class model must not be compacted against the 32k flat default).
        contextWindowTokens = AiConfigService.effectiveContextWindowTokens(transport.modelLabel());
        // Same snapshot for the output budget — the model's published cap (or the user's
        // explicit override), clamped per round by roundOptions against the headroom.
        modelMaxOutputTokens = AiConfigService.effectiveMaxOutputTokens(transport.modelLabel());
        // `cancelled` is re-armed in start on the caller thread (before this worker exists):
        // a cancel landing in the CAS→spawn gap then survives via the flag alone — the round-0
        // boundary check below aborts before any blocking call needs an interrupt.
        // Route A fallback: when the host could not transparently inject a FileRef, the model
        // sees the active files here and picks one. Route B injection flows via ChatFileContext
        // (set by AiController around this call) for the transparent path.
        String systemPrompt = fan.summer.fengyu.ai.workspace.WorkspacePromptAppender.append(
                ActiveFilesPromptAppender.append(effectiveSystemPrompt(), activeFileRefs));
        // Plan mode shapes the whole turn: the model investigates read-only and presents a
        // plan; non-READ tools still surface an approval card as the user's escape hatch.
        if (AiPermissionContext.current() == AiPermissionMode.PLAN) {
            systemPrompt = systemPrompt + """

                    ## Plan mode
                    You are in PLAN MODE: investigate read-only first (search, read, inspect),
                    then present a concise, structured plan (goal, steps, files touched, risks)
                    and wait for the user to approve it before doing any work that writes,
                    runs commands, or reaches outside services. Do not attempt write tools on
                    your own — they will be paused for approval. When the plan is approved,
                    execute it step by step using the todo list to track progress.""";
        }

        List<ToolCallback> currentTools = enableTools
                ? BoundToolsContext.mergeWith(transport.toolCallbackSupplier().get()) : List.of();

        // Dynamic tool loading (pi's setActiveTools pattern, gated by ai.tool_loading_mode /
        // ai.tool_loading_threshold): only a small always-attached core plus this
        // conversation's activation set is sent per round; the rest of the catalog is
        // advertised by name in the system prompt and activated on demand via the
        // search_tools loader. At or below the threshold — and in `off` mode — the full
        // catalog is attached exactly as before, byte for byte.
        boolean dynamicToolLoading = ToolLoadingPolicy.dynamicLoading(
                AiConfigService.getAiToolLoadingMode(),
                AiConfigService.getAiToolLoadingThreshold(),
                currentTools.size());
        ToolActivationState toolActivation = null;
        List<ToolCallback> attachedTools = currentTools;
        if (dynamicToolLoading) {
            toolActivation = ToolActivationState.seedFrom(history, ToolLoadingPolicy.toolNames(currentTools));
            attachedTools = ToolLoadingPolicy.attachedTools(currentTools, toolActivation);
            List<ToolCallback> deferred = ToolLoadingPolicy.deferredTools(currentTools, toolActivation);
            systemPrompt = ToolCatalogPromptAppender.append(systemPrompt, deferred);
            ToolActivationContext.set(toolActivation, deferred);
        }
        // Rollout recording state lives at method scope: the try body and the finally are
        // sibling scopes in Java, so anything the finally flushes must be declared here.
        AiRolloutService.Recorder rollout = null;
        String[] rolloutEnd = {"error"};
        int rolloutMark = history.size();
        int providerCompletionTokens = 0;
        try {
        // The Spring AI conversation is the source of truth sent to the model. It starts
        // from FengYu history; once tool calls happen, ToolCallingManager extends it
        // (assistant tool-call msg + ToolResponseMessage) and we carry that forward. The
        // overhead estimate counts only what is actually sent this turn (the attached set,
        // not the deferred catalog).
        ConversationCompactor.Result compaction = ConversationCompactor.compact(
                history, contextWindowTokens,
                promptOverheadTokens(systemPrompt, attachedTools), this::summarizeConversation);
        if (compaction.compacted()) {
            log.info("Compacted chat context: estimatedTokens={} -> {} (microcompact={})",
                    compaction.estimatedTokensBefore(), compaction.estimatedTokensAfter(),
                    compaction.microcompacted());
        }
        // Server-side rollout recording (terminal-agent practice): every turn over a real
        // conversation is appended to the JSONL log — messages, raw tool results, and both
        // compaction phases — so resume/fork work from server memory, not client PUTs.
        // Recording failures disable themselves; they can never break the turn.
        AiRolloutService rolloutService = transport.rolloutService();
        rollout = rolloutService == null ? null
                : rolloutService.start(
                        ConversationContext.current(),
                        transport.rolloutProvider(), transport.modelLabel());
        if (rollout != null) {
            if (compaction.compacted()) {
                rollout.compaction("turn_start", compaction.estimatedTokensBefore(),
                        compaction.estimatedTokensAfter(), -1, -1,
                        compaction.microcompacted(), false);
            }
            if (!history.isEmpty()
                    && history.getLast().role() == AiChatMessage.Role.USER) {
                rollout.message(history.getLast());
            }
        }
        emitUsage(compaction.history(), compaction.compacted(), compaction.microcompacted(),
                systemPrompt, attachedTools, contextWindowTokens, callback);
        List<Message> conversation = buildSpringAiMessages(compaction.history(), systemPrompt);
        // maxToolRounds bounds the number of tool-call rounds; 0 disables the safety net.
        // A loop counter alone cannot bound cost, but it stops a model that re-requests the
        // same tool forever from wedging this virtual thread and locking `generating`.
        // "Unlimited" (0) still hits the hard ceiling below — a looping model paired with an
        // auto-approve rule must not spin this thread forever.
        int effectiveMaxToolRounds = maxToolRounds > 0 ? maxToolRounds : HARD_MAX_TOOL_ROUNDS;
        long generationStartNanos = System.nanoTime();
        int activationVersion = toolActivation == null ? -1 : toolActivation.version();
        // Cache-prefix accounting baseline: the token estimate of the conversation as last
        // sent, so mid-turn scopes can separate cached prefix from fresh growth.
        int cacheBaseline = 0;
        for (int round = 0; round < effectiveMaxToolRounds; round++) {
            // Authoritative cancel gate: a tool may swallow the interrupt into a failure envelope
            // (BrowserTool.bridge catches all exceptions), so without this check the loop would
            // re-prompt the model with that failure and keep going. Checked at the top of every
            // round — the tightest boundary Spring AI's ToolCallingManager exposes to us.
            if (cancelled) {
                rolloutEnd[0] = "cancelled";
                throw new AiServiceException("cancelled");
            }
            if (toolActivation != null && toolActivation.version() != activationVersion) {
                // A search_tools result activated deferred tools mid-loop; rebuild the
                // attached set so the new definitions reach the model on THIS round.
                activationVersion = toolActivation.version();
                attachedTools = ToolLoadingPolicy.attachedTools(currentTools, toolActivation);
            }
            // Per-round options: in dynamic mode the attached set changes between rounds;
            // the output budget is clamped against the headroom this round's input leaves.
            // `conversation` already contains the system prompt — only tool overhead adds.
            ToolCallingChatOptions options = roundOptions(attachedTools, enableTools,
                    ConversationCompactor.estimateSpringTokens(conversation)
                            + toolsOverheadTokens(attachedTools));
            // When the endpoint has already rejected multimodal content, send a media-free
            // view of the conversation. `conversation` itself keeps the media messages so
            // history mirroring and the UI are unaffected — only the wire format degrades.
            List<Message> roundMessages = transport.mediaContentRejected()
                    ? ToolMediaBridge.withoutMedia(conversation) : conversation;
            Prompt prompt = options != null ? new Prompt(roundMessages, options) : new Prompt(roundMessages);

            // Stream this round; fire onToken per token delta; the aggregator hands us the
            // fully-assembled ChatResponse (including any tool calls) on completion.
            StringBuilder accumulated = new StringBuilder();
            AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
            Throwable streamError = streamWithRetry(prompt, accumulated, aggregated, callback);
            // Mid-stream cancellation: Reactor's cancel signal (dispose) delivers neither onError
            // nor onComplete, so streamError stays null and `aggregated` is absent — without this
            // gate the partial accumulated text below would terminate the turn as SUCCESS
            // (onComplete + staging export) even though the user cancelled. The interrupt race
            // (await() throwing first) funnels through here too.
            if (cancelled) {
                rolloutEnd[0] = "cancelled";
                throw new AiServiceException("cancelled");
            }
            if (streamError != null && transport.supportsMediaFallback()
                    && !transport.mediaContentRejected()
                    && accumulated.length() == 0
                    && ToolMediaBridge.containsMedia(conversation)
                    && ToolMediaBridge.isMediaContentRejection(streamError)) {
                // Strict gateway: retry this round once without image attachments instead of
                // failing the whole turn. Rejections happen before the first token, so nothing
                // was streamed to the UI yet; if the retry also fails, the original error wins.
                transport.markMediaContentRejected();
                log.warn("{} endpoint rejected multimodal (array-form) message content; "
                        + "continuing text-only — screenshots stay in the UI history",
                        transport.providerLabel());
                List<Message> stripped = ToolMediaBridge.withoutMedia(conversation);
                streamError = streamWithRetry(
                        options != null ? new Prompt(stripped, options) : new Prompt(stripped),
                        accumulated, aggregated, callback);
            }
            if (streamError != null) throw new AiServiceException(
                    transport.providerLabel() + " stream failed", streamError);

            ChatResponse roundResp = aggregated.get();
            boolean hasToolCalls = roundResp != null && roundResp.hasToolCalls();
            // Provider-reported usage beats text-length guesses; tool rounds' completions count
            // toward the turn total too.
            providerCompletionTokens += completionTokensOf(roundResp);

            if (!hasToolCalls) {
                String finalText = accumulated.toString();
                if (!finalText.isBlank()) history.add(finalAssistantMessage(finalText));
                rolloutEnd[0] = "complete";
                // Terminal refresh BEFORE onComplete (done closes the SSE stream): the
                // final answer and this turn's tool traffic are now in history, so the
                // UI meter ticks when the round closes instead of one turn late.
                emitUsage(history, compaction.compacted(), compaction.microcompacted(),
                        systemPrompt, attachedTools, contextWindowTokens, callback);
                int tokens = providerCompletionTokens > 0
                        ? providerCompletionTokens : Math.max(1, finalText.length() / 4);
                callback.onComplete(finalText, tokens, tokensPerSecond(tokens, generationStartNanos));
                return;
            }
            // User-controlled tool execution: let Spring AI's ToolCallingManager run the
            // requested tools (it resolves them against the options' toolCallbacks), firing
            // onToolCall/onToolResult for each so the UI shows tool progress.
            AssistantMessage assistantMsg = roundResp.getResult().getOutput();
            history.add(assistantMessage(accumulated.toString(), assistantMsg).withOrigin(originKey()));

            if (!allCallsAttached(assistantMsg, attachedTools)) {
                // Spring AI's ToolCallingManager throws IllegalStateException on a tool name it
                // cannot resolve, which would kill the whole turn. Answer the round with
                // actionable guidance instead — activate via search_tools, then retry — and
                // let the model re-request the calls (valid ones included) next round.
                fireToolCalls(assistantMsg, callback);
                conversation = appendUnknownToolGuidance(
                        conversation, history, assistantMsg, toolActivation, callback);
                continue;
            }
            ChatToolApprovalGate toolApprovalGate = transport.approvalGate();
            if (toolApprovalGate != null) {
                ChatToolApprovalGate.ApprovalBatch batch =
                        toolApprovalGate.awaitRequiredApprovals(assistantMsg, attachedTools, callback);
                fireToolCalls(assistantMsg, callback);
                if (!batch.isEmpty()) {
                    // A user rejection (optionally with feedback) answers the round with
                    // synthesized tool results — the model adjusts instead of losing the turn.
                    conversation = ChatToolApprovalGate.appendRejectedResults(
                            conversation, history, assistantMsg, batch, callback);
                    continue;
                }
            } else {
                fireToolCalls(assistantMsg, callback);
            }
            // User-controlled tool execution: let Spring AI's ToolCallingManager run the
            // requested tools (it resolves them against the options' toolCallbacks), firing
            // onToolCall/onToolResult for each so the UI shows tool progress. The batch
            // executor adds effect-grouped scheduling on top: concurrent READ runs,
            // exclusive WRITE/COMMAND calls — approvals already happened above, in
            // tool-call order.
            // Mid-execution approvals (the sandbox escape flow) reach tools through the
            // inheritable-context bridge, installed for exactly the batch like the
            // workspace/conversation contexts.
            ChatToolApprovalGate executionGate = toolApprovalGate;
            ToolApprovalContext.set(executionGate, callback, rollout);
            ToolExecutionResult result;
            try {
                result = ToolBatchExecutor.executeToolCalls(
                        toolCallingManager, prompt, assistantMsg, attachedTools);
            } finally {
                ToolApprovalContext.clear();
            }
            ToolMediaBridge.Result media = ToolMediaBridge.extract(result.conversationHistory());
            fireToolEvents(assistantMsg, media.messages(), callback);

            // Carry the manager's extended conversation (original msgs + assistant tool-call
            // msg + ToolResponseMessage) into the next round, and mirror tool results into
            // FengYu's own history for UI parity.
            conversation = ToolResultContextLimiter.limit(media.messages());
            mirrorToolResultsToHistory(conversation, history, assistantMsg, media.lastResponseMedia());
            if (rollout != null) {
                // Raw wire view first (the pre-limiter outputs), then the mirrored turn
                // delta as message events — exactly what a resume rebuilds from.
                for (int i = media.messages().size() - 1; i >= 0; i--) {
                    if (media.messages().get(i) instanceof ToolResponseMessage response) {
                        for (ToolResponseMessage.ToolResponse tool : response.getResponses()) {
                            rollout.toolResult(tool.id(), tool.name(), tool.responseData(), true,
                                    sandboxAuditOf(tool.responseData()), null);
                        }
                        break; // exactly one merged ToolResponseMessage is appended per round
                    }
                }
                for (int i = rolloutMark; i < history.size(); i++) {
                    rollout.message(history.get(i));
                }
                rolloutMark = history.size();
            }
            // Mid-turn compaction (between tool rounds): tool traffic only grows the
            // conversation from here, and an oversized round loses the whole turn. The cut
            // preserves the cacheable prefix (system + initial request) verbatim and keeps
            // the last tool rounds intact; the baseline tracks what the previous round sent
            // so the after-prefix scope says what the provider freshly pays.
            ConversationCompactor.MidTurnResult midTurn = ConversationCompactor.compactMidTurn(
                    conversation, contextWindowTokens,
                    cacheBaseline, this::summarizeConversation);
            if (midTurn.compacted()) {
                log.info("Mid-turn compaction: {} -> {} tokens (after-prefix scope {} -> {}{})",
                        midTurn.before().totalTokens(), midTurn.after().totalTokens(),
                        midTurn.before().afterPrefixTokens(), midTurn.after().afterPrefixTokens(),
                        midTurn.degraded() ? ", summarizer degraded to truncation" : "");
                conversation = midTurn.conversation();
                if (rollout != null) {
                    rollout.compaction("mid_turn",
                            midTurn.before().totalTokens(), midTurn.after().totalTokens(),
                            midTurn.before().afterPrefixTokens(), midTurn.after().afterPrefixTokens(),
                            false, midTurn.degraded());
                }
            }
            cacheBaseline = ConversationCompactor.estimateSpringTokens(conversation);
        }
        // Loop exhausted its budget without producing a tool-free answer.
        String warn = "Reached maxToolRounds (" + effectiveMaxToolRounds + ") without a final answer";
        log.warn(warn);
        callback.onError(new IllegalStateException(warn));
        } finally {
            if (rollout != null) {
                for (int i = rolloutMark; i < history.size(); i++) {
                    rollout.message(history.get(i));
                }
                rollout.end(rolloutEnd[0], providerCompletionTokens);
            }
            if (dynamicToolLoading) ToolActivationContext.clear();
        }
    }

    /**
     * The sandbox audit object a fenced tool embedded in its result JSON (workspace_exec
     * reports {@code {"sandbox":{backend,profile,…}}}); null when the output carries
     * none. Defensive parse — the log must never break on a tool's output shape.
     */
    private static Map<String, Object> sandboxAuditOf(String toolOutput) {
        if (toolOutput == null || !toolOutput.startsWith("{")) return null;
        try {
            Map<String, Object> parsed = fan.summer.fengyu.ai.util.JsonHelper.parseObject(toolOutput);
            return parsed.get("sandbox") instanceof Map<?, ?> sandbox
                    ? castSandbox(sandbox) : null;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castSandbox(Map<?, ?> sandbox) {
        return (Map<String, Object>) sandbox;
    }

    // ── shared helpers (moved verbatim from the two backends) ───────────────────────────

    /**
     * Round options with the given tool set attached and this round's output budget
     * clamped by the remaining context headroom. We MUST derive the options from the
     * transport's base options (the provider-specific OpenAiChatOptions /
     * AnthropicChatOptions / OllamaChatOptions the model was built with) via mutate(),
     * NOT a generic ToolCallingChatOptions.builder(): provider models cast
     * {@code prompt.getOptions()} to their own concrete type at request-build time, and
     * a DefaultToolCallingChatOptions throws ClassCastException. mutate() preserves the
     * concrete type. When base options are null (e.g. a plain ChatModel), fall back to a
     * generic ToolCallingChatOptions so the tools are still offered instead of
     * silently dropped.
     */
    private ToolCallingChatOptions roundOptions(List<ToolCallback> tools, boolean enableTools,
            int estimatedInputTokens) {
        ToolCallingChatOptions baseOptions = transport.baseOptions();
        ToolCallingChatOptions options = !enableTools || tools.isEmpty() ? baseOptions
                : baseOptions != null
                        ? baseOptions.mutate().toolCallbacks(tools.toArray(new ToolCallback[0])).build()
                        : ToolCallingChatOptions.builder()
                                .toolCallbacks(tools.toArray(new ToolCallback[0])).build();
        // Preflight output clamp (ZCode model-token-limits): the model's output cap is
        // only affordable while input + output still fits the window — near the edge the
        // budget shrinks with the headroom instead of losing the round to a 400.
        if (options != null && contextWindowTokens > 0 && modelMaxOutputTokens > 0) {
            int clamped = ConversationCompactor.clampMaxOutputTokens(
                    modelMaxOutputTokens, contextWindowTokens, estimatedInputTokens);
            if (clamped != modelMaxOutputTokens) {
                options = transport.withMaxTokens(options, clamped);
            }
        }
        return options;
    }

    /** History mirror of a tool-round assistant message, reasoning preserved when present. */
    private static AiChatMessage assistantMessage(String content, AssistantMessage original) {
        Object reasoning = original.getMetadata().get(REASONING_METADATA_KEY);
        List<AiToolCall> calls = mapToolCalls(original);
        return reasoning instanceof String text && !text.isBlank()
                ? AiChatMessage.assistantWithToolsAndReasoning(content, calls, text)
                : AiChatMessage.assistantWithTools(content, calls);
    }

    /**
     * History mirror of the final answer. Deliberately a PLAIN assistant message: only
     * TOOL rounds carry reasoning in the history (see {@link #assistantMessage}, which
     * replays it so DeepSeek-class endpoints accept the follow-up round); the final
     * round is never replayed into another request, so its reasoning is not attached.
     */
    private static AiChatMessage finalAssistantMessage(String content) {
        return AiChatMessage.assistant(content);
    }

    private static boolean allCallsAttached(AssistantMessage message, List<ToolCallback> attached) {
        if (message == null || !message.hasToolCalls()) return true;
        java.util.Set<String> names = ToolLoadingPolicy.toolNames(attached);
        return message.getToolCalls().stream()
                .allMatch(call -> call.name() != null && names.contains(call.name()));
    }

    /** Synthesizes tool results that guide the model to activate (not invent) tools. */
    private static List<Message> appendUnknownToolGuidance(List<Message> conversation,
            List<AiChatMessage> history, AssistantMessage assistantMsg,
            ToolActivationState activation, AiStreamCallback callback) {
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (AssistantMessage.ToolCall call : assistantMsg.getToolCalls()) {
            String id = call.id() != null && !call.id().isEmpty() ? call.id()
                    : "tc_" + SYNTHETIC_ID_SEQ.incrementAndGet();
            String guidance = unknownToolGuidance(call.name(), activation);
            responses.add(new ToolResponseMessage.ToolResponse(id, call.name(), guidance));
            callback.onToolResult(id, AiToolResult.error(guidance));
        }
        List<Message> extended = new ArrayList<>(conversation);
        extended.add(assistantMsg);
        extended.add(ToolResponseMessage.builder().responses(responses).build());
        mirrorToolResultsToHistory(extended, history, assistantMsg, List.of());
        return extended;
    }

    private static String unknownToolGuidance(String toolName, ToolActivationState activation) {
        if (activation != null && activation.isEligible(toolName)) {
            if (activation.isActive(toolName)) {
                return "Tool '" + toolName + "' is active but was not part of this round; retry the call now.";
            }
            return "Tool '" + toolName + "' exists but is not active. Call search_tools with a "
                    + "short keyword matching this tool; it becomes callable on your next message.";
        }
        return "No tool named '" + toolName + "' is available. Check the 'Available tools' catalog "
                + "in the system prompt and do not invent tool names.";
    }

    /**
     * {@link #streamAndCollect} under transient-error retry. A round is retried ONLY
     * while nothing has reached the UI (no text token, no thinking fragment) —
     * replaying a partially streamed answer would duplicate it in the transcript, so
     * post-emission (and post-cancel) failures are RETURNED AS VALUES, not thrown:
     * a returned value is a success to the retry engine, so no retry happens, and no
     * wrapper could do this job (the policy's exception filter traverses cause chains,
     * so a wrapper around a retryable error would still be retried). Thrown failures
     * surface from {@link RetryTemplate#execute} as {@link RetryException} whose cause
     * is the real error — the unwrap mirrors Spring AI's own {@code RetryUtils.execute}.
     */
    private Throwable streamWithRetry(Prompt prompt, StringBuilder accumulated,
                                      AtomicReference<ChatResponse> aggregated, AiStreamCallback callback) {
        try {
            return retryTemplate.execute(() -> {
                accumulated.setLength(0);
                aggregated.set(null);
                StreamEmissionGuard guard = new StreamEmissionGuard(callback);
                Throwable error = streamAndCollect(prompt, accumulated, aggregated, guard);
                if (error == null) return null;
                if (guard.emitted() || cancelled) return error;
                throw (error instanceof RuntimeException runtime) ? runtime : new RuntimeException(error);
            });
        } catch (RetryException e) {
            Throwable cause = e.getCause();
            return cause == null ? e : cause;
        }
    }

    /**
     * Latches whether anything was streamed to the UI during one attempt. Only the
     * stream-phase callbacks ({@code onToken}/{@code onThinking}) are delegated —
     * they are the only ones {@link #streamAndCollect} fires.
     */
    private static final class StreamEmissionGuard implements AiStreamCallback {
        private final AiStreamCallback delegate;
        private volatile boolean emitted;

        StreamEmissionGuard(AiStreamCallback delegate) { this.delegate = delegate; }

        boolean emitted() { return emitted; }

        @Override public void onToken(String fragment) {
            emitted = true;
            delegate.onToken(fragment);
        }

        @Override public void onThinking(String fragment) {
            emitted = true;
            delegate.onThinking(fragment);
        }
    }

    /**
     * Stream a prompt, fire onToken per token delta and onThinking per reasoning delta,
     * capture the aggregated response, and block the calling (virtual) thread until the
     * stream completes or is cancelled. The subscription {@link Disposable} is stored in
     * {@link #activeStream} so {@link #cancel()} can dispose it mid-stream;
     * {@link #streamDone} is counted down on terminal signals (complete/error/cancel)
     * to release the await below.
     */
    private Throwable streamAndCollect(Prompt prompt, StringBuilder accumulated,
                                       AtomicReference<ChatResponse> aggregated, AiStreamCallback callback) {
        streamDone = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        final ReasoningForwarder reasoning = transport.reasoningForwarder();
        activeStream = new MessageAggregator().aggregate(
                transport.chatModel().stream(prompt),
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
            // Reasoning arrives per the transport's fragment semantics (accumulated
            // chain-of-thought or per-chunk delta); the forwarder emits only the
            // never-seen suffix / verbatim fragment so the UI receives append-only deltas.
            reasoning.offer(am.getMetadata().get(transport.reasoningMetadataKey()), callback);
        }).subscribe(

                // onNext consumer — empty: doOnNext above already handled each element
                ignored -> { },
                // onError: stream failed
                error -> { failure.set(error); log.warn("{} stream error", transport.providerLabel(), error); streamDone.countDown(); },
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
        // DeepSeek-class thinking endpoints REJECT a follow-up round whose replayed
        // assistant message lacks reasoning_content ("must be passed back to the API").
        // The stream aggregator builds a fresh AssistantMessage without the metadata,
        // so re-attach the full reasoning here — the tool loop's conversation history
        // then carries it and the next request replays it (Spring AI maps the
        // "reasoningContent" metadata key to the reasoning_content wire field).
        if (failure.get() == null && !reasoning.total().isBlank()) {
            ChatResponse response = aggregated.get();
            if (response != null && response.getResult() != null
                    && response.getResult().getOutput() != null
                    && response.getResult().getOutput().getMetadata()
                            .get(REASONING_METADATA_KEY) == null) {
                AssistantMessage original = response.getResult().getOutput();
                AssistantMessage withReasoning = AssistantMessage.builder()
                        .content(original.getText())
                        .toolCalls(original.getToolCalls())
                        .properties(java.util.Map.of(REASONING_METADATA_KEY, reasoning.total()))
                        .build();
                aggregated.set(new ChatResponse(List.of(new Generation(withReasoning)),
                        response.getMetadata()));
            }
        }
        return failure.get();
    }

    /**
     * Producing-backend identity of the current transport ("provider/model"). Stamped on
     * tool-round assistant history so the next turn's normalizer can tell same-model
     * reasoning (replayable) from cross-model reasoning (wire-invalid).
     */
    private String originKey() {
        return transport.rolloutProvider() + "/" + transport.modelLabel();
    }

    private List<Message> buildSpringAiMessages(List<AiChatMessage> history, String systemPrompt) {
        // Outbound normalization (same-origin reasoning, wire-valid tool-call IDs, synthetic
        // results for orphaned calls, media downgrade on exact non-vision assertions).
        // FengYu history keeps its original shape — only this wire view is normalized.
        List<AiChatMessage> outbound = TranscriptNormalizer.normalize(history,
                new TranscriptNormalizer.Target(originKey(),
                        ModelMetadataCatalog.supportsImageExact(transport.modelLabel()).orElse(null)));
        List<Message> msgs = new ArrayList<>(outbound.size() + 1);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            msgs.add(new SystemMessage(systemPrompt));
        }
        for (AiChatMessage m : outbound) msgs.addAll(AiMessageBridge.toSpringAiMessages(m));
        return msgs;
    }

    private String summarizeConversation(String transcript) {
        Prompt prompt = transport.summarizePrompt(List.of(
                new SystemMessage(ConversationCompactor.SUMMARY_INSTRUCTIONS),
                new UserMessage(transcript)));
        // Blocking, nothing partial on the UI — transient errors retry whole-call.
        try {
            ChatResponse response = retryTemplate.execute(() -> transport.chatModel().call(prompt));
            if (response == null || response.getResult() == null
                    || response.getResult().getOutput() == null) return "";
            return response.getResult().getOutput().getText();
        } catch (RetryException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new RuntimeException(cause);
        }
    }

    /** Provider-reported completion tokens of one round; 0 when the stream carried no usage. */
    private static int completionTokensOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null) return 0;
        org.springframework.ai.chat.metadata.Usage usage = response.getMetadata().getUsage();
        if (usage == null || usage.getCompletionTokens() == null) return 0;
        return Math.max(0, usage.getCompletionTokens());
    }

    private static double tokensPerSecond(int tokens, long startNanos) {
        double seconds = (System.nanoTime() - startNanos) / 1_000_000_000d;
        return seconds > 0 ? tokens / seconds : 0;
    }

    private static int promptOverheadTokens(String systemPrompt, List<ToolCallback> tools) {
        long estimate = ConversationCompactor.estimateTextTokens(systemPrompt);
        return (int) Math.min(Integer.MAX_VALUE, estimate + toolsOverheadTokens(tools));
    }

    /** Tool-definition overhead alone — the clamp estimate pairs it with a Spring-message
     *  list that ALREADY contains the system prompt, so the system text must not recur. */
    private static int toolsOverheadTokens(List<ToolCallback> tools) {
        long estimate = 0;
        for (ToolCallback tool : tools) {
            var definition = tool.getToolDefinition();
            estimate += 12L + ConversationCompactor.estimateTextTokens(definition.name())
                    + ConversationCompactor.estimateTextTokens(definition.description())
                    + ConversationCompactor.estimateTextTokens(definition.inputSchema());
        }
        return (int) Math.min(Integer.MAX_VALUE, estimate);
    }

    /** One context-usage snapshot for the UI indicator, against this turn's resolved window. */
    private static void emitUsage(List<AiChatMessage> history, boolean compacted,
                                  boolean microcompacted, String systemPrompt,
                                  List<ToolCallback> attachedTools, int contextWindowTokens,
                                  AiStreamCallback callback) {
        int estimate = (int) Math.min(Integer.MAX_VALUE,
                (long) ConversationCompactor.estimateTokens(history)
                        + promptOverheadTokens(systemPrompt, attachedTools));
        callback.onUsage(new AiStreamCallback.ContextUsage(estimate, contextWindowTokens,
                compacted, microcompacted));
    }

    private static List<AiToolCall> mapToolCalls(AssistantMessage am) {
        if (am == null || !am.hasToolCalls()) return List.of();
        List<AiToolCall> out = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
            String id = tc.id() != null && !tc.id().isEmpty() ? tc.id()
                    : "tc_" + SYNTHETIC_ID_SEQ.incrementAndGet();
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
    private static void fireToolEvents(AssistantMessage assistantMsg, List<Message> messages,
                                       AiStreamCallback callback) {
        ToolResponseMessage trm = lastToolResponseMessage(messages);
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
                                                   AssistantMessage assistantMsg,
                                                   List<List<fan.summer.fengyu.ai.AiMedia>> responseMedia) {
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
                    tc.name(), tr.responseData(), i < responseMedia.size()
                            ? responseMedia.get(i) : List.of()));
        }
    }

    private String currentSystemPrompt() {
        try { return AiConfigServiceHeadless.getAiSystemPrompt(); }
        catch (Throwable t) { return null; }
    }

    /**
     * The effective system prompt: the user-configured base prompt with the enabled-skills
     * catalog appended (progressive disclosure). When no skills are enabled, or the registry
     * is unset, the base prompt is returned unchanged. Delegates to
     * {@link SkillPromptAppender} so every transport stays in lock-step.
     */
    private String effectiveSystemPrompt() {
        return SkillPromptAppender.append(currentSystemPrompt(), transport.skillRegistry());
    }
}
