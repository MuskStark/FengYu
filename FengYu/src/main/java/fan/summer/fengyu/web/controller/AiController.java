package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiStreamCallback;
import fan.summer.fengyu.ai.AiToolCall;
import fan.summer.fengyu.ai.AiToolResult;
import fan.summer.fengyu.ai.ChatArtifactStore;
import fan.summer.fengyu.ai.ChatBackend;
import fan.summer.fengyu.ai.ChatFileContext;
import fan.summer.fengyu.ai.ChatFileContext.ActiveFileRef;
import fan.summer.fengyu.ai.ChatFileGrantService;
import fan.summer.fengyu.ai.ChatResourceScopeService;
import fan.summer.fengyu.ai.service.AiConfigServiceHeadless;
import fan.summer.fengyu.ai.service.AiModeService;
import fan.summer.fengyu.ai.service.OllamaLocalBackend;
import fan.summer.fengyu.ai.tools.ChatToolApprovalGate;
import fan.summer.fengyu.ai.tools.AiPermissionContext;
import fan.summer.fengyu.ai.tools.AiPermissionMode;
import fan.summer.fengyu.ai.tools.AiToolLocaleContext;
import fan.summer.fengyu.ai.tools.BoundToolsContext;
import fan.summer.fengyu.plugin.market.ManifestI18n;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.tool.ToolCallback;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;
import java.time.Instant;

/**
 * AI chat over Server-Sent Events. AI chat is a permanent core built-in — never routed through
 * the plugin {@code invoke} path.
 *
 * <p>Flow: {@code POST /api/ai/chat} accepts the conversation, stashes it under a random
 * {@code streamId}, and returns it. {@code GET /api/ai/stream?streamId=...} opens an
 * {@link SseEmitter} (EventSource-compatible, GET-only) and drives the chat, bridging
 * {@link AiStreamCallback} events to SSE events: {@code token}, {@code thinking}, {@code tool},
 * {@code done}, {@code error}.
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    private final AiModeService aiMode;
    private final ChatToolApprovalGate toolApprovalGate;
    private final ChatFileGrantService fileGrants;
    private final PluginFileGrantService pluginFiles;
    private final fan.summer.fengyu.web.StreamTicketService streamTickets;
    /** Source of the request-bound {@code run_current_flow} tool; optional in headless test contexts. */
    private final ObjectProvider<fan.summer.fengyu.ai.config.AiToolRegistry> toolRegistry;
    /** Conversation-scoped chat resources; null only in legacy unit-test constructions. */
    private final fan.summer.fengyu.ai.ChatResourceScopeService resourceScopes;
    /** Host-side save closure for generated artifacts; null only in legacy unit-test constructions. */
    private final fan.summer.fengyu.ai.ChatArtifactStore chatArtifacts;
    private final fan.summer.fengyu.security.SecurityContext security;
    /** Workspace binding source for coding turns; null only in legacy unit-test constructions. */
    private final fan.summer.fengyu.ai.workspace.WorkspaceService workspaces;

    public AiController(AiModeService aiMode, ChatToolApprovalGate toolApprovalGate,
            ChatFileGrantService fileGrants, PluginFileGrantService pluginFiles,
            fan.summer.fengyu.web.StreamTicketService streamTickets) {
        this(aiMode, toolApprovalGate, fileGrants, pluginFiles, streamTickets, null);
    }

    public AiController(AiModeService aiMode, ChatToolApprovalGate toolApprovalGate,
            ChatFileGrantService fileGrants, PluginFileGrantService pluginFiles,
            fan.summer.fengyu.web.StreamTicketService streamTickets,
            ObjectProvider<fan.summer.fengyu.ai.config.AiToolRegistry> toolRegistry) {
        this(aiMode, toolApprovalGate, fileGrants, pluginFiles, streamTickets, toolRegistry,
                null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AiController(AiModeService aiMode, ChatToolApprovalGate toolApprovalGate,
            ChatFileGrantService fileGrants, PluginFileGrantService pluginFiles,
            fan.summer.fengyu.web.StreamTicketService streamTickets,
            ObjectProvider<fan.summer.fengyu.ai.config.AiToolRegistry> toolRegistry,
            fan.summer.fengyu.ai.ChatResourceScopeService resourceScopes,
            fan.summer.fengyu.ai.ChatArtifactStore chatArtifacts,
            fan.summer.fengyu.security.SecurityContext security,
            fan.summer.fengyu.ai.workspace.WorkspaceService workspaces) {
        this.aiMode = aiMode;
        this.toolApprovalGate = toolApprovalGate;
        this.fileGrants = fileGrants;
        this.pluginFiles = pluginFiles;
        this.streamTickets = streamTickets;
        this.toolRegistry = toolRegistry;
        this.resourceScopes = resourceScopes;
        this.chatArtifacts = chatArtifacts;
        this.security = security;
        this.workspaces = workspaces;
    }

    public AiController(AiModeService aiMode, ChatToolApprovalGate toolApprovalGate,
            ChatFileGrantService fileGrants, PluginFileGrantService pluginFiles,
            fan.summer.fengyu.web.StreamTicketService streamTickets,
            ObjectProvider<fan.summer.fengyu.ai.config.AiToolRegistry> toolRegistry,
            fan.summer.fengyu.ai.ChatResourceScopeService resourceScopes,
            fan.summer.fengyu.ai.ChatArtifactStore chatArtifacts,
            fan.summer.fengyu.security.SecurityContext security) {
        this(aiMode, toolApprovalGate, fileGrants, pluginFiles, streamTickets, toolRegistry,
                resourceScopes, chatArtifacts, security, null);
    }

    /** The calling user for scope-ownership checks; the local single-user model when absent. */
    private Long callerUserId() {
        try {
            return security == null ? null : security.currentUserId();
        } catch (RuntimeException noUser) {
            return null;
        }
    }

    /**
     * Mints the one-time ticket {@code GET /api/ai/stream} redeems via {@code ?ticket=}
     * (EventSource cannot send the header token; a ticket authorizes exactly one stream
     * connection and never reaches URL logs as the full credential).
     */
    @org.springframework.web.bind.annotation.PostMapping("/stream-ticket")
    public Map<String, Object> streamTicket() {
        var issued = streamTickets.issue(fan.summer.fengyu.web.StreamTicketService.AI_STREAM_ENDPOINT);
        return Map.of("ticket", issued.ticket(), "expiresAt", issued.expiresAt().toString());
    }

    /** Pending turns keyed by streamId; consumed once when the SSE opens. */
    private final Map<String, PendingTurn> pending = new ConcurrentHashMap<>();

    /**
     * Drops pending turns created before {@code cutoff} (each POST /chat sweeps turns abandoned
     * without ever opening their stream). Reclaims ONLY the turn-scoped staging plus the scoped
     * resource lease: client attachments and persistent grants already handed over with the POST
     * response have owners elsewhere, and revoking them from here would break the client's next
     * turn at validate().
     */
    void sweepExpiredPendingTurns(Instant cutoff) {
        pending.entrySet().removeIf(entry -> {
            if (!entry.getValue().createdAt().isBefore(cutoff)) return false;
            fileGrants.discardStaging(entry.getValue().staged());
            releaseTurnLease(entry.getValue());
            return true;
        });
    }

    /** B03: an abandoned POST must not leave an orphaned resource lease either. */
    private void releaseTurnLease(PendingTurn turn) {
        if (resourceScopes != null && turn.scopeId() != null && turn.leaseId() != null) {
            try {
                resourceScopes.releaseLease(turn.scopeId(), turn.leaseId());
            } catch (RuntimeException ignored) {
                // scope already closed — closeScope reclaimed everything
            }
        }
    }
    private final AtomicReference<String> activeStreamId = new AtomicReference<>();
    /**
     * The backend instance actually driving the active generation, captured at stream start.
     * {@link AiModeService#getService()} reflects the currently-configured provider, so if the user
     * switches backend mid-stream the value at cancel() time could differ from the one generating —
     * a cancel would hit the wrong backend and the real generation would run orphaned. Holding the
     * exact instance ensures cancel targets the generation we started.
     */
    private final AtomicReference<ChatBackend> activeBackend = new AtomicReference<>();

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest req,
            @RequestHeader(name = "Accept-Language", required = false) String acceptLanguage) {
        sweepExpiredPendingTurns(Instant.now().minus(Duration.ofMinutes(10)));
        // 429 (not a 500): the cap is load shedding against the caller, and the message must
        // read as "retry later", not "server bug".
        if (pending.size() >= 100) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "Too many pending AI streams");
        List<AiChatMessage> history = new ArrayList<>();
        if (req.messages() != null) {
            for (ChatMessageDto m : req.messages()) {
                history.add(toDomain(m));
            }
        }
        // Ref ownership, scoped vs. legacy:
        //  - scoped (scopeId set): the turn resolves resources through the scope registry — the
        //    server, not the frontend, decides which grants exist. Bare activeFileRefs are
        //    rejected so a scoped page can never fall back to unowned grants (5.2).
        //  - legacy (Flow runs and their chat panels): the caller owns the refs it sends and the
        //    persistent refs this POST mints for typed paths, handed over with the response.
        //  - stagingRefs stay turn-scoped either way (revoked at the terminal); for scoped turns
        //    the terminal collects them as host-managed artifacts instead of blind copies.
        String scopeId = req.scopeId() == null || req.scopeId().isBlank() ? null : req.scopeId();
        String sendId = req.sendId() == null || req.sendId().isBlank() ? null : req.sendId();
        Long caller = callerUserId();
        String leaseId = null;
        boolean sendCommitStarted = false;
        List<ActiveFileRef> clientRefs = new ArrayList<>();
        if (scopeId != null) {
            if (resourceScopes == null) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "Scoped chat resources are not available");
            }
            if (req.activeFileRefs() != null && !req.activeFileRefs().isEmpty()) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "A scoped chat turn must reference resources by id, not raw file refs");
            }
            // Idempotence gate (E05/E06): a retry of an already-committed sendId replays the
            // accepted response — never a second message, lease, or model call.
            if (sendId != null) {
                java.util.Map<String, Object> replay =
                        resourceScopes.replayableResult(scopeId, caller, sendId);
                if (replay != null) return replay;
            }
            resourceScopes.bindConversation(scopeId, caller, req.conversationId());
        } else if (req.activeFileRefs() != null) {
            for (ActiveFileRefDto dto : req.activeFileRefs()) {
                pluginFiles.validate(dto.pluginId(), dto.ref());
                clientRefs.add(new ActiveFileRef(dto.pluginId(), dto.ref()));
            }
        }
        // Flow builder turns may bind two kinds of request-scoped tools: non-mutating authoring
        // tools over the LIVE canvas (including unsaved/invalid graphs), and run_current_flow over
        // a clean saved definition. Build and validate both BEFORE any grant/staging side effect:
        // an invalid workflow id must not leave issued grants or staging directories behind.
        List<ToolCallback> boundTools = new ArrayList<>();
        String locale = ManifestI18n.resolveLocale(acceptLanguage);
        String workflowId = req.workflowId() == null ? "" : req.workflowId().trim();
        if (req.flowContext() != null) {
            String contextWorkflowId = req.flowContext().get("workflowId") == null ? ""
                    : String.valueOf(req.flowContext().get("workflowId")).trim();
            if (!workflowId.equals(contextWorkflowId)) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "Flow context workflowId does not match the chat workflowId");
            }
            var registry = toolRegistry == null ? null : toolRegistry.getIfAvailable();
            if (registry == null) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "Workflow tools are not available");
            }
            try {
                boundTools.addAll(registry.boundFlowAuthoringTools(req.flowContext(), locale));
            } catch (RuntimeException e) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, e.getMessage());
            }
        }
        if (!workflowId.isBlank() && !Boolean.TRUE.equals(
                req.flowContext() == null ? null : req.flowContext().get("dirty"))) {
            var registry = toolRegistry == null ? null : toolRegistry.getIfAvailable();
            if (registry == null) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "Workflow tools are not available");
            }
            try {
                Object expectedRevision = req.flowContext() == null
                        ? null : req.flowContext().get("revision");
                if (!(expectedRevision instanceof Number)
                        || registry.workflowRevisionMatches(workflowId, expectedRevision)) {
                    boundTools.add(registry.boundWorkflowTool(workflowId));
                }
            } catch (RuntimeException e) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, e.getMessage());
            }
        }
        // A path typed into the composer is just as explicit as a picker selection. Resolve only
        // the latest USER message, only when it names an existing absolute path, then turn it into
        // normal plugin-scoped grants. The model can never create grants by mentioning a path in
        // an assistant/tool message. Scoped turns adopt these grants as registry-owned resources
        // (invariant 5.3-7); legacy turns hand them to the client with the response.
        //
        // For a write target — typed in the message, or the scope's registered output location —
        // a plugin-owned staging directory is created per write-capable plugin. The staging grant
        // joins ONLY the turn's active refs; the terminal collects it as host-managed artifacts
        // (scope turns) or copies it to the typed target (legacy turns).
        List<ActiveFileRef> persistentRefs = new ArrayList<>();
        List<ActiveFileRef> stagingRefs = new ArrayList<>();
        List<ChatFileGrantService.StagedOutput> staged;
        List<ChatResourceScopeService.Resource> adopted = List.of();
        String userText = latestUserText(req.messages());
        try {
            if (scopeId != null) {
                // Commit bracket (§15.2): begin moves the send's prepared copies into the scope,
                // adoption adds typed paths with a rollback journal, the lease derives this turn's
                // FileRefs over ALL of them, and finish records the response for idempotent
                // replay. Any failure before finish rolls the whole send back.
                if (sendId != null) {
                    resourceScopes.beginCommit(scopeId, caller, sendId);
                    sendCommitStarted = true;
                }
                adopted = resourceScopes.adoptTextInput(scopeId, caller, userText, sendId);
                List<String> turnResourceIds = new ArrayList<>(
                        req.resourceIds() == null ? List.of() : req.resourceIds());
                if (sendId != null) {
                    turnResourceIds.addAll(resourceScopes.sendResourceIds(scopeId, caller, sendId));
                }
                for (ChatResourceScopeService.Resource resource : adopted) {
                    turnResourceIds.add(resource.resourceId());
                }
                if (!turnResourceIds.isEmpty()) {
                    ChatResourceScopeService.Lease lease =
                            resourceScopes.acquireLease(scopeId, caller, turnResourceIds);
                    leaseId = lease.leaseId();
                    clientRefs.addAll(lease.refs());
                }
            } else {
                persistentRefs.addAll(fileGrants.grantPathsFromUserText(userText));
            }
            List<Path> writeTargets = new ArrayList<>(ChatFileGrantService.writeTargetsIn(userText));
            if (scopeId != null) {
                String scopeTarget = resourceScopes.outputTarget(scopeId);
                if (scopeTarget != null && writeTargets.stream().noneMatch(
                        path -> path.toString().equals(scopeTarget))) {
                    writeTargets.add(Path.of(scopeTarget));
                }
            }
            ChatFileGrantService.StagingPreparation preparation =
                    fileGrants.prepareStagingForTargets(writeTargets);
            stagingRefs.addAll(preparation.refs());
            staged = preparation.staged();
        } catch (RuntimeException e) {
            // Reclaim only what THIS request minted (staging partials are revoked inside the
            // preparation itself); the client's attachments stay untouched. A scoped turn
            // additionally releases its lease and rolls the send transaction back, so a record
            // whose copy was just reclaimed never stays resolvable and the draft is all that
            // remains (E03/E04).
            for (ActiveFileRef ref : persistentRefs) pluginFiles.revoke(ref.pluginId(), ref.ref().id());
            if (scopeId != null) {
                if (leaseId != null) {
                    try {
                        resourceScopes.releaseLease(scopeId, leaseId);
                    } catch (RuntimeException ignored) {
                        // the scope may itself be the failure — closeScope already reclaimed it
                    }
                }
                if (sendCommitStarted) {
                    try {
                        resourceScopes.failSend(scopeId, caller, sendId,
                                "The chat turn failed to start");
                    } catch (RuntimeException ignored) {
                        // same — closeScope already reclaimed everything this send owned
                    }
                } else {
                    for (ChatResourceScopeService.Resource resource : adopted) {
                        try {
                            resourceScopes.removeResource(scopeId, caller, resource.resourceId());
                        } catch (RuntimeException ignored) {
                            // the scope may itself be the failure — closeScope already reclaimed it
                        }
                    }
                }
            }
            throw e;
        }
        List<ActiveFileRef> activeRefs = new ArrayList<>(clientRefs);
        activeRefs.addAll(persistentRefs);
        activeRefs.addAll(stagingRefs);
        // Coding workspace binding: resolved server-side from the conversation the request
        // names, never from a client-supplied path. A conversation without an attached root
        // (or a legacy flow turn) simply runs unbound — the coding tools stay hidden.
        fan.summer.fengyu.ai.workspace.WorkspaceContext.Binding workspace =
                workspaces == null ? null : workspaces.bindingFor(req.conversationId());
        String streamId = UUID.randomUUID().toString();
        pending.put(streamId, new PendingTurn(history, activeRefs, staged,
                AiPermissionMode.from(req.permissionMode()), locale,
                Instant.now(), List.copyOf(boundTools), scopeId, leaseId, workspace));
        if (scopeId != null) {
            // Scoped hand-over: the response carries the aggregated resource records (never raw
            // refs); the frontend merges them into the owning conversation. Recording it closes
            // the send transaction — after this, a same-sendId retry replays instead of resending.
            java.util.Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("streamId", streamId);
            response.put("activeFileRefs", List.of());
            response.put("resources", resourceScopes.snapshot(scopeId, caller).resources().stream()
                    .map(ChatResourceController::resourceDto).toList());
            if (sendId != null) {
                resourceScopes.finishCommit(scopeId, caller, sendId, response);
            }
            return response;
        }
        // Legacy hand-over: exactly the persistent grants — the response is the ownership
        // boundary: after it, the client may legitimately resend these next turn; staging dies
        // with the turn.
        List<ActiveFileRefDto> responseRefs = persistentRefs.stream()
            .map(ref -> new ActiveFileRefDto(ref.pluginId(), ref.ref())).toList();
        return Map.of("streamId", streamId, "activeFileRefs", responseRefs);
    }

    @PostMapping("/tool-approvals/{approvalId}")
    public Map<String, Object> resolveToolApproval(@PathVariable String approvalId,
                                                   @RequestBody ToolApprovalDecision decision) {
        boolean resolved = toolApprovalGate.resolve(approvalId, decision.approved());
        return resolved
                ? Map.of("ok", true, "approved", decision.approved())
                : Map.of("ok", false, "error", "Unknown, expired, or already resolved approval");
    }

    @PostMapping("/cancel")
    public Map<String, Object> cancel(@RequestParam String streamId) {
        if (!activeStreamId.compareAndSet(streamId, null)) {
            return Map.of("ok", false, "error", "Stream is not the active generation");
        }
        // Cancel the backend instance that is actually generating, not whatever is configured now
        // (the user may have switched provider in another tab since the stream started).
        ChatBackend backend = activeBackend.getAndSet(null);
        if (backend != null) {
            backend.cancelGeneration();
        }
        return Map.of("ok", true, "streamId", streamId);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String streamId) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout — chat length is unbounded
        PendingTurn turn = pending.remove(streamId);
        if (turn == null) {
            // Terminal + machine-distinguishable (CQ-02): a consumed/expired streamId can
            // never be replayed (the first connection consumed it and a transport
            // disconnect cancelled the generation), so the client must STOP retrying the
            // same id — the code carries that decision.
            completeWithError(emitter, "Unknown or expired streamId", "unknown_stream");
            return emitter;
        }
        List<AiChatMessage> history = turn.history();
        // One lease per consumed turn: whichever way the stream ends (success terminal, model
        // error, transport disconnect, or a failure before the backend ever starts), exactly one
        // of complete/abort reclaims the turn's staging — nothing leaks and nothing double-runs.
        // A success terminal first collects the staging as host-managed artifacts (saving them
        // into the turn's captured target), then releases the scope's resource lease.
        TurnLease lease = new TurnLease(fileGrants, chatArtifacts, resourceScopes,
                turn.scopeId(), turn.leaseId(), turn.staged());

        Optional<ChatBackend> svc = aiMode.getService();
        if (svc.isEmpty()) {
            lease.abort();
            completeWithError(emitter, "AI backend not configured");
            return emitter;
        }
        ChatBackend backend = svc.get();
        // Local (Ollama) backends resolve their ChatModel lazily in loadModel; trigger it on
        // first chat so isReady() can flip to true. After Task 3's BackendReactivator the backend
        // is registered at startup but never loadModel'd — without this, local mode always errored
        // as "not configured or not ready".
        if (!backend.isReady() && backend instanceof OllamaLocalBackend ob) {
            try {
                ob.loadModel(null);
            } catch (Exception e) {
                lease.abort();
                completeWithError(emitter, "Ollama backend not ready: " + e.getMessage());
                return emitter;
            }
        }
        if (!backend.isReady()) {
            lease.abort();
            completeWithError(emitter, "AI backend not ready (check provider config and connection)");
            return emitter;
        }
        if (!activeStreamId.compareAndSet(null, streamId)) {
            // The turn is no longer in `pending`, so nothing else would ever reclaim it.
            lease.abort();
            completeWithError(emitter, "Another AI generation is already in progress");
            return emitter;
        }
        activeBackend.set(backend);

        Runnable releaseActiveStream = () -> {
            activeStreamId.compareAndSet(streamId, null);
            activeBackend.compareAndSet(backend, null);
        };
        AtomicBoolean generationStarted = new AtomicBoolean();
        SseCallback streamCallback = new SseCallback(emitter, () -> {
            lease.complete();
            releaseActiveStream.run();
        }, () -> {
            // A failed model turn must not export partial outputs into the user's target.
            lease.abort();
            releaseActiveStream.run();
        }, () -> {
            // A transport disconnect has no model callback to release the active slot. Cancel the
            // exact backend captured for this turn and release it here; SseCallback guarantees this
            // path runs at most once even when completion/error callbacks race a failed send.
            lease.abort();
            if (activeStreamId.compareAndSet(streamId, null) && generationStarted.get()) {
                backend.cancelGeneration();
            }
            activeBackend.compareAndSet(backend, null);
        });
        // Open the transport only after all close callbacks are registered. If this first write
        // already fails, open() runs the same disconnect path and the backend is never started.
        if (!streamCallback.open()) return emitter;
        try {
            // Set the per-turn file context BEFORE chat() so the singleton plugin ToolCallbacks
            // (Task 3's AiToolFileInjector) can read it during synchronous tool execution. The
            // virtual-thread worker runs chat() inline under this binding, so the ThreadLocal is
            // visible for the whole tool-execution window. Cleared in finally to avoid leakage.
            ChatFileContext.set(turn.activeFileRefs());
            AiPermissionContext.set(turn.permissionMode());
            AiToolLocaleContext.set(turn.locale());
            BoundToolsContext.set(turn.boundTools());
            fan.summer.fengyu.ai.workspace.WorkspaceContext.set(turn.workspace());
            streamCallback.start(() -> {
                svc.get().chat(history,
                        AiConfigServiceHeadless.getAiTemperature(),
                        AiConfigServiceHeadless.getAiTopP(),
                        AiConfigServiceHeadless.getAiMaxTokens(),
                        turn.activeFileRefs(),
                        // onComplete routes to the SseCallback's completed path (staging export),
                        // onError to the failed path (staging discard) — both release the slot.
                        streamCallback);
                generationStarted.set(true);
            });
        } catch (Exception e) {
            // Also stops the heartbeat if chat() fails synchronously before its worker starts.
            streamCallback.onError(e);
        } finally {
            ChatFileContext.clear();
            AiPermissionContext.clear();
            AiToolLocaleContext.clear();
            BoundToolsContext.clear();
            fan.summer.fengyu.ai.workspace.WorkspaceContext.clear();
        }
        return emitter;
    }

    // ── AiStreamCallback → SSE bridge ──────────────────────────────────────────────────

    static final class SseCallback implements AiStreamCallback {
        private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(10);

        private final SseEmitter emitter;
        private final Runnable completed;
        private final Runnable failed;
        private final Runnable disconnected;
        private final AtomicBoolean finished = new AtomicBoolean();
        private final Thread heartbeatThread;
        private final Object lifecycleLock = new Object();

        SseCallback(SseEmitter emitter, Runnable completed, Runnable failed, Runnable disconnected) {
            this.emitter = emitter;
            this.completed = completed;
            this.failed = failed;
            this.disconnected = disconnected;
            // Approval can legitimately leave the stream otherwise silent for minutes. Keep
            // Electron/WebView and intermediate HTTP stacks from treating that idle period as a
            // dead SSE connection; a dropped frontend stream calls /cancel, which would reject
            // the pending approval and surface a misleading ToolApprovalException.
            this.heartbeatThread = Thread.ofVirtual().name("ai-sse-heartbeat").unstarted(() -> {
                while (!finished.get()) {
                    try {
                        Thread.sleep(HEARTBEAT_INTERVAL);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (!finished.get()) sendComment("heartbeat");
                }
            });
            emitter.onCompletion(this::disconnect);
            emitter.onTimeout(this::disconnect);
            emitter.onError(ignored -> disconnect());
            heartbeatThread.start();
        }

        /** Flush the initial SSE frame after disconnect callbacks are installed. */
        boolean open() {
            sendComment("connected");
            return !finished.get();
        }

        /** Prevent a disconnect from racing between the open check and backend startup. */
        boolean start(StartAction action) throws Exception {
            synchronized (lifecycleLock) {
                if (finished.get()) return false;
                action.run();
                return true;
            }
        }

        @Override public void onToken(String fragment) {
            send("token", Map.of("text", fragment == null ? "" : fragment));
        }

        @Override public void onThinking(String fragment) {
            send("thinking", Map.of("text", fragment == null ? "" : fragment));
        }

        @Override public void onToolCall(AiToolCall toolCall) {
            send("tool", Map.of("phase", "call", "id", toolCall.id() == null ? "" : toolCall.id(), "name", toolCall.name(),
                "arguments", toolCall.arguments() == null ? Map.of() : toolCall.arguments()));
        }

        @Override
        public void onToolApprovalRequired(String approvalId, AiToolCall toolCall,
                                           java.time.Instant expiresAt) {
            send("tool", Map.of(
                    "phase", "approval_required",
                    "approvalId", approvalId,
                    "id", toolCall.id() == null ? "" : toolCall.id(),
                    "name", toolCall.name(),
                    "arguments", toolCall.arguments() == null ? Map.of() : toolCall.arguments(),
                    "expiresAt", expiresAt.toString()));
        }

        @Override public void onToolResult(String toolCallId, AiToolResult result) {
            send("tool", Map.of("phase", "result", "id", toolCallId == null ? "" : toolCallId,
                "success", result.success(), "output", result.output() == null ? "" : result.output()));
        }

        @Override public void onComplete(String fullResponse, int tokensGenerated, double tokensPerSecond) {
            finish("done", Map.of("text", fullResponse == null ? "" : fullResponse,
                    "tokens", tokensGenerated, "tps", tokensPerSecond));
        }

        @Override public void onError(Throwable error) {
            finish("error", Map.of("message",
                    error == null ? "unknown" : String.valueOf(error.getMessage())));
        }

        private void finish(String event, Object data) {
            synchronized (lifecycleLock) {
                if (!finished.compareAndSet(false, true)) return;
            }
            heartbeatThread.interrupt();
            // For the success terminal, settle resources BEFORE the event leaves: the client
            // reacts to "done" by listing artifacts, so registration (and target auto-save)
            // must already be durable — no retry race between the event and the file work.
            if ("done".equals(event)) {
                completed.run();
                send(event, data);
                emitter.complete();
            } else {
                // Every other finish (model error, sync throw) takes the failure path — partial
                // staging outputs are never collected.
                send(event, data);
                emitter.complete();
                failed.run();
            }
        }

        private void send(String event, Object data) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                log.debug("SSE send failed ({}): {}", event, e.getMessage());
                disconnect();
            }
        }

        private void sendComment(String comment) {
            try {
                emitter.send(SseEmitter.event().comment(comment));
            } catch (IOException | IllegalStateException e) {
                log.debug("SSE heartbeat failed: {}", e.getMessage());
                disconnect();
            }
        }

        private void disconnect() {
            synchronized (lifecycleLock) {
                if (!finished.compareAndSet(false, true)) return;
            }
            heartbeatThread.interrupt();
            disconnected.run();
        }

        /**
         * Test visibility: whether the heartbeat thread is still alive. Every terminal
         * path ({@link #finish}, {@link #disconnect} — the latter wired to the emitter's
         * onCompletion/onTimeout/onError) interrupts the heartbeat, so the thread must
         * die whenever the emitter terminates.
         */
        boolean heartbeatAlive() {
            return heartbeatThread.isAlive();
        }

        @FunctionalInterface
        interface StartAction {
            void run() throws Exception;
        }
    }

    /** Sends a terminal {@code error} event then completes the emitter. */
    private void completeWithError(SseEmitter emitter, String message) {
        completeWithError(emitter, message, null);
    }

    /**
     * Sends a terminal {@code error} event then completes the emitter. {@code code} is a
     * machine-readable discriminator added to the payload (null for plain human-readable
     * errors) so a client can react programmatically — e.g. {@code unknown_stream} means
     * the streamId is consumed/expired and retrying it can never succeed.
     */
    private void completeWithError(SseEmitter emitter, String message, String code) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("message", message == null ? "unknown" : message);
        if (code != null) payload.put("code", code);
        try {
            emitter.send(SseEmitter.event().name("error")
                .data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            log.debug("SSE error send failed: {}", e.getMessage());
        }
        emitter.complete();
    }

    private static AiChatMessage toDomain(ChatMessageDto m) {
        String role = m.role() == null ? "user" : m.role();
        String content = m.content() == null ? "" : m.content();
        return switch (role) {
            case "system" -> AiChatMessage.system(content);
            case "assistant" -> AiChatMessage.assistant(content);
            default -> AiChatMessage.user(content);
        };
    }

    private static String latestUserText(List<ChatMessageDto> messages) {
        if (messages == null) return "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto message = messages.get(i);
            if (message != null && (message.role() == null || "user".equals(message.role()))) {
                return message.content() == null ? "" : message.content();
            }
        }
        return "";
    }

    // ── DTOs ────────────────────────────────────────────────────────────────────────────

    public record ChatRequest(List<ChatMessageDto> messages, List<ActiveFileRefDto> activeFileRefs,
                              String permissionMode, String workflowId,
                              Map<String, Object> flowContext, String scopeId,
                              List<String> resourceIds, Long conversationId, String sendId) {
        public ChatRequest(List<ChatMessageDto> messages, List<ActiveFileRefDto> activeFileRefs) {
            this(messages, activeFileRefs, null, null, null, null, null, null, null);
        }
        public ChatRequest(List<ChatMessageDto> messages, List<ActiveFileRefDto> activeFileRefs,
                           String permissionMode) {
            this(messages, activeFileRefs, permissionMode, null, null, null, null, null, null);
        }
        public ChatRequest(List<ChatMessageDto> messages, List<ActiveFileRefDto> activeFileRefs,
                           String permissionMode, String workflowId) {
            this(messages, activeFileRefs, permissionMode, workflowId, null, null, null, null, null);
        }
        public ChatRequest(List<ChatMessageDto> messages, List<ActiveFileRefDto> activeFileRefs,
                           String permissionMode, String workflowId, Map<String, Object> flowContext) {
            this(messages, activeFileRefs, permissionMode, workflowId, flowContext, null, null, null, null);
        }
        public ChatRequest(List<ChatMessageDto> messages, List<ActiveFileRefDto> activeFileRefs,
                           String permissionMode, String workflowId, Map<String, Object> flowContext,
                           String scopeId, List<String> resourceIds, Long conversationId) {
            this(messages, activeFileRefs, permissionMode, workflowId, flowContext,
                    scopeId, resourceIds, conversationId, null);
        }
    }
    public record ChatMessageDto(String role, String content) {}
    public record ToolApprovalDecision(boolean approved) {}
    public record ActiveFileRefDto(String pluginId, PluginFileGrantService.FileRef ref) {}

    /**
     * Carries a stashed turn's history + active file refs from {@code POST /chat} to
     * {@code GET /stream}. {@code boundTools} holds the request-scoped tool callbacks
     * (the flow-bound {@code run_current_flow}) built and validated eagerly at POST time.
     * {@code scopeId}/{@code leaseId} are set for scoped turns so the terminal (or the pending
     * sweep) can release the resource lease.
     */
    private record PendingTurn(List<AiChatMessage> history, List<ActiveFileRef> activeFileRefs,
                               List<ChatFileGrantService.StagedOutput> staged,
                               AiPermissionMode permissionMode, String locale, Instant createdAt,
                               List<ToolCallback> boundTools, String scopeId, String leaseId,
                               fan.summer.fengyu.ai.workspace.WorkspaceContext.Binding workspace) {}

    /**
     * Owns one consumed turn's terminal resource handling. Exactly one of {@link #complete()}
     * (success terminal: collect the staging as host-managed artifacts, auto-saving into the
     * turn's captured target) / {@link #abort()} (every other end: pre-start failure, model
     * error, cancellation, transport disconnect — discard staging) ever runs, no matter how the
     * racing SSE callbacks arrive. Both paths release the scoped resource lease (B03/B04).
     */
    static final class TurnLease {
        private final ChatFileGrantService grants;
        private final ChatArtifactStore artifacts;
        private final ChatResourceScopeService scopes;
        private final String scopeId;
        private final String leaseId;
        private final List<ChatFileGrantService.StagedOutput> staged;
        private final AtomicBoolean done = new AtomicBoolean();

        TurnLease(ChatFileGrantService grants, ChatArtifactStore artifacts,
                ChatResourceScopeService scopes, String scopeId, String leaseId,
                List<ChatFileGrantService.StagedOutput> staged) {
            this.grants = grants;
            this.artifacts = artifacts;
            this.scopes = scopes;
            this.scopeId = scopeId;
            this.leaseId = leaseId;
            this.staged = staged;
        }

        void complete() {
            if (!done.compareAndSet(false, true)) return;
            try {
                if (artifacts != null) {
                    Long conversationId = scopes == null || scopeId == null
                            ? null : scopes.conversationIdOf(scopeId);
                    artifacts.completeTurn(scopeId, conversationId, staged);
                } else {
                    grants.exportStaging(staged);
                }
            } finally {
                releaseScope();
            }
        }

        void abort() {
            if (!done.compareAndSet(false, true)) return;
            try {
                grants.discardStaging(staged);
            } finally {
                releaseScope();
            }
        }

        private void releaseScope() {
            if (scopes != null && scopeId != null && leaseId != null) {
                try {
                    scopes.releaseLease(scopeId, leaseId);
                } catch (RuntimeException ignored) {
                    // scope already closed — closeScope reclaimed everything
                }
            }
        }
    }
}
