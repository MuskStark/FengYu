package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiMedia;
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
 * {@code streamId}, and returns it (possibly {@code queued:true} while this conversation
 * already streams — the active stream's {@code done} event then carries
 * {@code nextStreamId}). {@code GET /api/ai/stream?streamId=...} opens an
 * {@link SseEmitter} (EventSource-compatible, GET-only) and drives the chat, bridging
 * {@link AiStreamCallback} events to SSE events: {@code token}, {@code thinking}, {@code
 * tool}, {@code usage} (final token accounting), {@code done}, {@code error}.
 *
 * <p>One active stream <b>per conversation</b> (4.1.0): turns of different conversations
 * generate in parallel; a conversation serializes its own turns — extras park in a
 * per-conversation queue and continue via the terminal's {@code nextStreamId}.
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
     * Conversation-scoped send queues: a POST arriving while the
     * SAME conversation already streams parks its turn here instead of failing. The
     * terminal {@code done} event carries the popped successor's streamId; the frontend
     * opens it, which reuses the normal per-conversation active-stream machinery.
     */
    private final Map<Long, java.util.ArrayDeque<String>> sendQueues = new ConcurrentHashMap<>();
    private static final int MAX_QUEUE_PER_CONVERSATION = 3;
    /**
     * The in-flight generations, keyed by streamId — one active stream PER conversation
     * since 4.1.0; turns of different conversations run in parallel. Null-conversation
     * (flow-panel) turns register only here and never contend with conversation turns.
     */
    private final ConcurrentHashMap<String, ActiveGeneration> activeGenerations = new ConcurrentHashMap<>();
    /**
     * The streamId owning each conversation's active generation — the same-conversation
     * serialization decision. Mutated atomically with the queue park/pop decisions and
     * the stream gate under {@link #queueLock} (removals are additionally safe lock-free
     * via {@code remove(key, value)}).
     */
    private final Map<Long, String> activeByConversation = new ConcurrentHashMap<>();
    /**
     * Guards every sendQueues/activeByConversation transition (park decision, gate
     * register, terminal pop/clear, discard, sweep). Without it a park decision could
     * observe the active conversation mid-terminal and park a turn whose pop already
     * happened — wedging the conversation's queue until the sweep. ArrayDeque itself is
     * not thread-safe either.
     */
    private final Object queueLock = new Object();

    /**
     * One in-flight generation: the turn-scoped cancel handle (set the moment chat()
     * returns; before that a cancel only marks {@code cancelRequested} and the handle is
     * cancelled right after it appears — no orphaned-generation window). {@code backend}
     * is retained for logging/diagnostics only; cancellation is handle-scoped and thus
     * immune to mid-stream provider switches by construction.
     */
    static final class ActiveGeneration {
        final ChatBackend backend;
        final Long conversationId;
        private volatile ChatBackend.GenerationHandle handle;
        private volatile boolean cancelRequested;

        ActiveGeneration(ChatBackend backend, Long conversationId) {
            this.backend = backend;
            this.conversationId = conversationId;
        }

        void setHandle(ChatBackend.GenerationHandle handle) {
            this.handle = handle;
            // A cancel that landed before chat() returned must not orphan the generation.
            if (cancelRequested) handle.cancel();
        }

        void requestCancel() {
            cancelRequested = true;
            ChatBackend.GenerationHandle current = handle;
            if (current != null) current.cancel();
        }
    }

    /** Terminal release: drop the stream from both tables (idempotent, per-stream). */
    private void releaseActiveGeneration(String streamId, Long conversationId) {
        activeGenerations.remove(streamId);
        if (conversationId != null) activeByConversation.remove(conversationId, streamId);
    }

    /**
     * One disconnected stream's cleanup: abort the turn lease, cancel the turn's own
     * generation (a no-op after a terminal already released it — map removals are
     * idempotent), free the conversation slot, and drop its queued turns. Package-private
     * for the lost-terminal regression test.
     *
     * <p>Also reclaims a successor popped by a SUCCESS terminal whose {@code done} event
     * never left the wire (the client vanished exactly at completion — see
     * {@code SseCallback.finish}): that successor's only delivery vehicle was the lost
     * {@code nextStreamId}, nothing else will ever open it, and leaving it parked would
     * wedge the conversation until the 10-minute pending sweep.
     */
    void cleanupDisconnect(TurnLease lease, String streamId, ActiveGeneration generation,
            PendingTurn turn, AtomicReference<String> queuedSuccessor) {
        lease.abort();
        if (activeGenerations.remove(streamId) != null) generation.requestCancel();
        if (turn.conversationId() != null) {
            activeByConversation.remove(turn.conversationId(), streamId);
        }
        discardQueuedFor(turn.conversationId());
        String popped = queuedSuccessor.get();
        if (popped != null) {
            PendingTurn successor = pending.remove(popped);
            if (successor != null) {
                fileGrants.discardStaging(successor.staged());
                releaseTurnLease(successor);
            }
        }
    }

    private void discardQueuedId(Long conversationId, String streamId) {
        if (conversationId == null) return;
        synchronized (queueLock) {
            java.util.ArrayDeque<String> queue = sendQueues.get(conversationId);
            if (queue != null) queue.remove(streamId);
        }
    }

    /** Pops the next queued streamId of {@code conversationId} (null when the queue is empty). */
    private String popQueued(Long conversationId) {
        if (conversationId == null) return null;
        synchronized (queueLock) {
            java.util.ArrayDeque<String> queue = sendQueues.get(conversationId);
            return queue == null ? null : queue.poll();
        }
    }

    /**
     * Drops every queued turn of one conversation (a failed or disconnected terminal): each
     * parked pending turn gets its staging and lease reclaimed so nothing half-contextual
     * survives. The frontend mirrors this by clearing its local queue on error.
     */
    private void discardQueuedFor(Long conversationId) {
        if (conversationId == null) return;
        List<String> dropped = new ArrayList<>();
        synchronized (queueLock) {
            java.util.ArrayDeque<String> queue = sendQueues.remove(conversationId);
            if (queue != null) dropped.addAll(queue);
        }
        for (String streamId : dropped) {
            PendingTurn turn = pending.remove(streamId);
            if (turn != null) {
                fileGrants.discardStaging(turn.staged());
                releaseTurnLease(turn);
            }
        }
    }

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
            discardQueuedId(entry.getValue().conversationId(), entry.getKey());
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
            // Inline images ride ONLY the last user turn: older images are already answered
            // (their conclusions live on in the text) and re-sending every historical image
            // each turn inflates the provider context quadratically with conversation length.
            int lastUser = -1;
            for (int i = 0; i < req.messages().size(); i++) {
                ChatMessageDto m = req.messages().get(i);
                if (m != null && (m.role() == null || "user".equals(m.role()))) lastUser = i;
            }
            for (int i = 0; i < req.messages().size(); i++) {
                history.add(toDomain(req.messages().get(i), i == lastUser));
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
        Long conversationId = req.conversationId();
        String streamId = UUID.randomUUID().toString();
        // Flow/workflow panels render no question cards, so ask_user there would block the
        // turn on its timeout with nobody able to answer — hide it from those surfaces.
        java.util.List<String> hiddenTools = req.flowContext() != null || !workflowId.isBlank()
                ? java.util.List.of(fan.summer.fengyu.ai.tools.AskUserTool.NAME)
                : java.util.List.of();
        pending.put(streamId, new PendingTurn(history, activeRefs, staged,
                AiPermissionMode.from(req.permissionMode()), locale,
                Instant.now(), List.copyOf(boundTools), scopeId, leaseId, workspace,
                conversationId, hiddenTools));
        // Queued sends: while THIS conversation already streams (or has queued turns), park
        // the new turn instead of racing the per-conversation gate. The terminal `done`
        // event names the successor; a queued turn nobody opens expires via the sweep.
        // The decision is atomic with the terminal clear/pop under queueLock so a POST can
        // never park into a window whose pop already happened. (Other conversations are
        // NOT blocked — each streams its own active generation in parallel.)
        boolean queued = false;
        boolean queueFull = false;
        int queuePosition = 0;
        if (conversationId != null) {
            synchronized (queueLock) {
                java.util.ArrayDeque<String> queue = sendQueues.computeIfAbsent(
                        conversationId, id -> new java.util.ArrayDeque<>());
                boolean busyForThisConversation = !queue.isEmpty()
                        || activeByConversation.containsKey(conversationId);
                if (busyForThisConversation && queue.size() < MAX_QUEUE_PER_CONVERSATION) {
                    queue.add(streamId);
                    queuePosition = queue.size();
                    queued = true;
                } else if (busyForThisConversation) {
                    // The queue is at its cap: the turn is NOT parked (the client may open
                    // its stream, which parks back with a conversation_busy error). The
                    // flag lets the client detect this without that extra round-trip.
                    queueFull = true;
                }
            }
        }
        if (scopeId != null) {
            // Scoped hand-over: the response carries the aggregated resource records (never raw
            // refs); the frontend merges them into the owning conversation. Recording it closes
            // the send transaction — after this, a same-sendId retry replays instead of resending.
            java.util.Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("streamId", streamId);
            response.put("activeFileRefs", List.of());
            response.put("queued", queued);
            if (queued) response.put("queuePosition", queuePosition);
            if (queueFull) response.put("queueFull", true);
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
        java.util.Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("streamId", streamId);
        response.put("activeFileRefs", responseRefs);
        response.put("queued", queued);
        if (queued) response.put("queuePosition", queuePosition);
        if (queueFull) response.put("queueFull", true);
        return response;
    }

    @PostMapping("/tool-approvals/{approvalId}")
    public Map<String, Object> resolveToolApproval(@PathVariable String approvalId,
                                                   @RequestBody ToolApprovalDecision decision) {
        // Fail-closed: a body-less POST never resolves as an approval.
        boolean approved = Boolean.TRUE.equals(decision.approved());
        ChatToolApprovalGate.Decision gateDecision = new ChatToolApprovalGate.Decision(
                approved, Boolean.TRUE.equals(decision.always()), decision.feedback());
        boolean resolved = toolApprovalGate.resolve(gateDecision, approvalId);
        return resolved
                ? Map.of("ok", true, "approved", approved)
                : Map.of("ok", false, "error", "Unknown, expired, or already resolved approval");
    }

    /** Answer body of {@code POST /questions/{id}}: the user's per-question selections. */
    public record QuestionAnswer(java.util.List<Map<String, Object>> answers) {}

    /** Resolves an outstanding ask_user question with the user's answers. */
    @PostMapping("/questions/{questionId}")
    public Map<String, Object> resolveQuestion(@PathVariable String questionId,
                                               @RequestBody QuestionAnswer answer) {
        java.util.List<Map<String, Object>> answers = answer == null ? null : answer.answers();
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("answers", answers == null ? java.util.List.of() : answers);
        boolean resolved = toolApprovalGate.resolveQuestion(questionId, payload);
        return resolved
                ? Map.of("ok", true)
                : Map.of("ok", false, "error", "Unknown, expired, or already answered question");
    }

    /**
     * Discards queued (never-opened) turns — the frontend calls this when the user stops a
     * generation or removes a queued message. Discarding an ACTIVE stream is refused; use
     * {@code POST /cancel} for that.
     */
    @PostMapping("/queue/discard")
    public Map<String, Object> discardQueued(@RequestBody QueueDiscardRequest request) {
        int discarded = 0;
        if (request.streamIds() != null) {
            for (String streamId : request.streamIds()) {
                // An ACTIVE generation is not "queued" — cancelling it goes through /cancel.
                if (streamId == null || activeGenerations.containsKey(streamId)) continue;
                PendingTurn turn = pending.remove(streamId);
                if (turn != null) {
                    fileGrants.discardStaging(turn.staged());
                    releaseTurnLease(turn);
                    discardQueuedId(turn.conversationId(), streamId);
                    discarded++;
                }
            }
        }
        return Map.of("ok", true, "discarded", discarded);
    }

    /**
     * Cancels one in-flight generation by streamId — exactly the turn whose handle the
     * stream captured, so a provider switch mid-stream or a parallel turn of another
     * conversation can never redirect the cancel.
     */
    @PostMapping("/cancel")
    public Map<String, Object> cancel(@RequestParam String streamId) {
        ActiveGeneration generation;
        synchronized (queueLock) {
            generation = activeGenerations.remove(streamId);
        }
        if (generation == null) {
            return Map.of("ok", false, "error", "Stream is not an active generation");
        }
        // The conversation's active slot is released by THIS turn's own terminal (the
        // SseCallback error/disconnect path fires when the cancelled stream dies), which
        // also owns the queue discard — cancelling here would race a same-conversation
        // POST that legitimately re-registered the slot.
        generation.requestCancel();
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
        // Same-conversation serialization: the gate check + slot registration is atomic
        // with the POST park decision and the terminal clear/pop under queueLock. A
        // same-conversation POST that raced in and grabbed the just-freed slot must
        // not silently EAT this turn: park it back at the queue head — the running
        // stream's terminal names it again as the successor (FIFO kept; a failed or
        // disconnected terminal discards it with the rest of the queue, and the sweep
        // reclaims it if nobody ever opens it). The lease stays open on purpose: the
        // turn's staging is released by whichever path consumes it next. Different
        // conversations never reach this branch — each runs its own generation.
        ActiveGeneration generation = new ActiveGeneration(backend, turn.conversationId());
        if (turn.conversationId() != null) {
            boolean parkedBack = false;
            synchronized (queueLock) {
                if (activeByConversation.containsKey(turn.conversationId())) {
                    // Remove any earlier park of the SAME id first: a client re-opening a
                    // parked stream while still busy would otherwise enqueue it twice —
                    // the terminal's pop would then name the id as its own successor.
                    pending.put(streamId, turn);
                    java.util.ArrayDeque<String> queue = sendQueues.computeIfAbsent(
                            turn.conversationId(), id -> new java.util.ArrayDeque<>());
                    queue.remove(streamId);
                    queue.addFirst(streamId);
                    parkedBack = true;
                } else {
                    // Slot + generation register as ONE critical section so /cancel (which
                    // removes under the same lock) observes either both or neither — a
                    // cancel landing between the two puts would silently miss the turn.
                    activeByConversation.put(turn.conversationId(), streamId);
                    activeGenerations.put(streamId, generation);
                }
            }
            if (parkedBack) {
                completeWithError(emitter,
                        "Another generation is already streaming in this conversation",
                        "conversation_busy");
                return emitter;
            }
        } else {
            // Flow-panel turn (no conversation): registers only the generation table and
            // never contends with conversation turns.
            activeGenerations.put(streamId, generation);
        }
        // The successor a queued send waits for: popped BEFORE the done event leaves (the
        // completed runnable runs first in SseCallback.finish), so the frontend can open it
        // immediately — the conversation's active slot is already released by then.
        AtomicReference<String> queuedSuccessor = new AtomicReference<>();

        Runnable releaseActiveStream = () -> releaseActiveGeneration(streamId, turn.conversationId());
        SseCallback streamCallback = new SseCallback(emitter, () -> {
            lease.complete();
            // Clear the conversation's active slot and pop the successor as ONE atomic
            // transition: a POST parking in between would wedge (its pop already happened).
            synchronized (queueLock) {
                releaseActiveStream.run();
                queuedSuccessor.set(popQueued(turn.conversationId()));
            }
        }, () -> {
            // A failed model turn must not export partial outputs into the user's target.
            // Queued sends stop with it — their parked turns are reclaimed outright.
            lease.abort();
            releaseActiveStream.run();
            discardQueuedFor(turn.conversationId());
        }, () -> {
            // A transport disconnect has no model callback to release the slot; SseCallback
            // guarantees this path runs at most once even when completion/error callbacks
            // race a failed send. The same cleanup also covers a SUCCESS terminal whose
            // done event never left the wire (see cleanupDisconnect).
            cleanupDisconnect(lease, streamId, generation, turn, queuedSuccessor);
        }, () -> {
            java.util.Map<String, Object> extras = new java.util.LinkedHashMap<>();
            String successor = queuedSuccessor.get();
            if (successor != null) extras.put("nextStreamId", successor);
            return extras;
        }, () -> backend.getModelName().orElse(null));
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
            BoundToolsContext.setHidden(turn.hiddenTools());
            fan.summer.fengyu.ai.workspace.WorkspaceContext.set(turn.workspace());
            fan.summer.fengyu.ai.tools.ConversationContext.set(turn.conversationId());
            streamCallback.start(() -> {
                generation.setHandle(svc.get().chat(history,
                        AiConfigServiceHeadless.getAiTemperature(),
                        AiConfigServiceHeadless.getAiTopP(),
                        AiConfigServiceHeadless.getAiMaxTokens(),
                        turn.activeFileRefs(),
                        // onComplete routes to the SseCallback's completed path (staging export),
                        // onError to the failed path (staging discard) — both release the slot.
                        streamCallback));
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
            fan.summer.fengyu.ai.tools.ConversationContext.clear();
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
        /** Extra fields merged into the terminal {@code done} payload (e.g. nextStreamId). */
        private final java.util.function.Supplier<Map<String, Object>> doneExtras;
        /** Active model id supplier for the terminal cost estimate; null skips it. */
        private final java.util.function.Supplier<String> modelName;
        private final AtomicBoolean finished = new AtomicBoolean();
        private final Thread heartbeatThread;
        private final Object lifecycleLock = new Object();

        SseCallback(SseEmitter emitter, Runnable completed, Runnable failed, Runnable disconnected) {
            this(emitter, completed, failed, disconnected, Map::of, null);
        }

        SseCallback(SseEmitter emitter, Runnable completed, Runnable failed, Runnable disconnected,
                java.util.function.Supplier<Map<String, Object>> doneExtras) {
            this(emitter, completed, failed, disconnected, doneExtras, null);
        }

        SseCallback(SseEmitter emitter, Runnable completed, Runnable failed, Runnable disconnected,
                java.util.function.Supplier<Map<String, Object>> doneExtras,
                java.util.function.Supplier<String> modelName) {
            this.emitter = emitter;
            this.completed = completed;
            this.failed = failed;
            this.disconnected = disconnected;
            this.doneExtras = doneExtras;
            this.modelName = modelName;
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

        @Override
        public void onQuestionRequired(String questionId, Map<String, Object> payload,
                java.time.Instant expiresAt) {
            java.util.Map<String, Object> event = new java.util.LinkedHashMap<>();
            event.put("questionId", questionId);
            event.put("questions", payload == null || payload.get("questions") == null
                    ? java.util.List.of() : payload.get("questions"));
            event.put("expiresAt", expiresAt.toString());
            send("question", event);
        }

        @Override public void onToolResult(String toolCallId, AiToolResult result) {
            send("tool", Map.of("phase", "result", "id", toolCallId == null ? "" : toolCallId,
                "success", result.success(), "output", result.output() == null ? "" : result.output()));
        }

        @Override public void onUsage(AiStreamCallback.ContextUsage usage) {
            send("usage", Map.of(
                    "contextTokens", usage.contextTokens(),
                    "contextWindowTokens", usage.contextWindowTokens(),
                    "compacted", usage.compacted(),
                    "microcompacted", usage.microcompacted()));
        }

        @Override public void onComplete(String fullResponse, int tokensGenerated, double tokensPerSecond) {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("text", fullResponse == null ? "" : fullResponse);
            payload.put("tokens", tokensGenerated);
            payload.put("tps", tokensPerSecond);
            // D2: rough OUTPUT-side cost estimate from catalog list prices (input
            // tokens are not settled per turn here); absent when the model is unpriced.
            if (modelName != null) {
                fan.summer.fengyu.ai.config.ModelMetadataCatalog.costFor(modelName.get())
                        .ifPresent(rates -> payload.put("outputCostEstimate",
                                rates.outputCostUsd(tokensGenerated)));
            }
            finish("done", payload);
        }

        @Override public void onError(Throwable error) {
            // D1 error contract: the additive `errorCode` lets the UI key retry/hint
            // behavior off the code; the message stays display-only.
            finish("error", java.util.Map.of(
                    "message", error == null ? "unknown" : String.valueOf(error.getMessage()),
                    "errorCode", fan.summer.fengyu.ai.AiErrorCode.of(error).name()));
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
                // Extras merge AFTER completed.run(): the queued-successor pop happens inside
                // the completed runnable, so doneExtras.get() would still be stale here if it
                // were read earlier (round-2 review: nextStreamId was never emitted).
                if (doneExtras != null && data instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = (Map<String, Object>) data;
                    payload.putAll(doneExtras.get());
                }
                if (!trySend(event, data)) {
                    // The success terminal never left the wire — the client vanished exactly
                    // at completion. The successor id it carried is lost with that client
                    // and the slot release already happened, so run the DISCONNECT cleanup
                    // (drops the conversation's remaining queued turns and reclaims the
                    // popped successor): otherwise the queue wedges until the 10-minute
                    // pending sweep. The disconnect path is idempotent against the
                    // completed terminal's own releases.
                    disconnected.run();
                }
                emitter.complete();
            } else {
                // Every other finish (model error, sync throw) takes the failure path —
                // partial staging outputs are never collected. Settle BEFORE the event
                // leaves (the done branch's contract): the slot release and the queued
                // discard happen first, so a same-conversation POST that reacts to the
                // error can never park into a queue this terminal is about to drop.
                failed.run();
                send(event, data);
                emitter.complete();
            }
        }

        private void send(String event, Object data) {
            if (!trySend(event, data)) disconnect();
        }

        /** One best-effort event send; false when the client is gone (never throws). */
        private boolean trySend(String event, Object data) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
                return true;
            } catch (IOException | IllegalStateException e) {
                log.debug("SSE send failed ({}): {}", event, e.getMessage());
                return false;
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

    private static AiChatMessage toDomain(ChatMessageDto m, boolean includeImages) {
        String role = m.role() == null ? "user" : m.role();
        String content = m.content() == null ? "" : m.content();
        return switch (role) {
            case "system" -> AiChatMessage.system(content);
            case "assistant" -> AiChatMessage.assistant(content);
            default -> {
                List<AiMedia> media = includeImages ? toMedia(m.images()) : List.of();
                yield media.isEmpty() ? AiChatMessage.user(content)
                        : AiChatMessage.userWithMedia(content, media);
            }
        };
    }

    /** Hard caps on inline vision input: count and per-image base64 size. */
    private static final int MAX_INLINE_IMAGES = 4;
    private static final int MAX_INLINE_IMAGE_BASE64_CHARS = 4 * 1024 * 1024;

    /** Bounded conversion of inline image DTOs into {@link AiMedia}; invalid entries drop. */
    private static List<AiMedia> toMedia(List<MediaDto> images) {
        if (images == null || images.isEmpty()) return List.of();
        List<AiMedia> out = new ArrayList<>();
        for (MediaDto image : images) {
            if (image == null || image.base64Data() == null || image.base64Data().isBlank()) continue;
            if (image.base64Data().length() > MAX_INLINE_IMAGE_BASE64_CHARS) continue;
            String mime = image.mimeType() == null || image.mimeType().isBlank()
                    ? "image/png" : image.mimeType();
            out.add(new AiMedia(mime, image.base64Data(),
                    image.name() == null || image.name().isBlank() ? "image" : image.name()));
            if (out.size() >= MAX_INLINE_IMAGES) break;
        }
        return out;
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
    public record ChatMessageDto(String role, String content, List<MediaDto> images) {
        public ChatMessageDto(String role, String content) {
            this(role, content, List.of());
        }
    }

    /** One inline image attached to a user message (vision input); base64, never a URL. */
    public record MediaDto(String name, String mimeType, String base64Data) {}

    /** Upgraded approval decision: approve/reject × once/always, optional denial feedback. */
    public record ToolApprovalDecision(Boolean approved, Boolean always, String feedback) {
        public ToolApprovalDecision(boolean approved) {
            this(approved, null, null);
        }
    }

    /** Body of {@code POST /queue/discard}. */
    public record QueueDiscardRequest(List<String> streamIds) {}
    public record ActiveFileRefDto(String pluginId, PluginFileGrantService.FileRef ref) {}

    /**
     * Carries a stashed turn's history + active file refs from {@code POST /chat} to
     * {@code GET /stream}. {@code boundTools} holds the request-scoped tool callbacks
     * (the flow-bound {@code run_current_flow}) built and validated eagerly at POST time.
     * {@code scopeId}/{@code leaseId} are set for scoped turns so the terminal (or the pending
     * sweep) can release the resource lease.
     */
    /** Package-private for the disconnect-cleanup regression test. */
    record PendingTurn(List<AiChatMessage> history, List<ActiveFileRef> activeFileRefs,
                               List<ChatFileGrantService.StagedOutput> staged,
                               AiPermissionMode permissionMode, String locale, Instant createdAt,
                               List<ToolCallback> boundTools, String scopeId, String leaseId,
                               fan.summer.fengyu.ai.workspace.WorkspaceContext.Binding workspace,
                               Long conversationId, List<String> hiddenTools) {}

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
