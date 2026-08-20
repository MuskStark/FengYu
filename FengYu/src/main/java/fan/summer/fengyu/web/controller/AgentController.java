package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.ChatFileContext;
import fan.summer.fengyu.ai.agent.AgentEventSink;
import fan.summer.fengyu.ai.agent.AgentPlan;
import fan.summer.fengyu.ai.agent.AgentRun;
import fan.summer.fengyu.ai.agent.AgentRunConfig;
import fan.summer.fengyu.ai.agent.AgentRunPersistenceService;
import fan.summer.fengyu.ai.agent.AgentRunRegistry;
import fan.summer.fengyu.ai.agent.AgentRunner;
import fan.summer.fengyu.ai.config.AiToolRegistry;
import fan.summer.fengyu.ai.workflow.WorkflowDefinition;
import fan.summer.fengyu.ai.workflow.WorkflowExecutionService;
import fan.summer.fengyu.ai.workflow.WorkflowRevisionSummary;
import fan.summer.fengyu.ai.workflow.WorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * HTTP + SSE layer for the Plan-and-Execute agent (Task 16).
 *
 * <p>Exposes five endpoints over {@code /api/agent}:
 * <ul>
 *   <li>{@code POST /api/agent/run} — start a run; returns {@code {"runId":"..."}}.</li>
 *   <li>{@code GET /api/agent/stream?runId=} — open an {@link SseEmitter} and receive the
 *       run's lifecycle events (plan tokens, plan ready, step start/complete, approvals,
 *       complete/error) as {@code text/event-stream} chunks named after the event.</li>
 *   <li>{@code POST /api/agent/{runId}/approve} — release the run's approval gate, optionally
 *       with an edited plan body.</li>
 *   <li>{@code POST /api/agent/{runId}/cancel} — flip the run's cancellation flag.</li>
 *   <li>{@code GET /api/agent/tools} — the orchestrable tool list (name/description/
 *       input/output schemas) sourced from the live {@link AiToolRegistry}
 *       (consumed by the agent UI and visual workflow canvas).</li>
 * </ul>
 *
 * <h2>SSE buffering</h2>
 * <p>{@link #run(AgentRunRequest)} starts the runner on a virtual thread immediately, so plan
 * tokens (and even {@code onPlanReady}) can arrive <em>before</em> the client opens
 * {@code /stream}. The {@link AgentStreamSink} for a run buffers every event in a
 * {@link CopyOnWriteArrayList} until the controller attaches an {@link SseEmitter} (on the
 * {@code GET /stream} call); once attached, the buffer is drained to the client and
 * subsequent events are pushed live. This mirrors {@code AiController}'s streamId/stash
 * pattern but generalized to a continuous event stream rather than a single consumed-once
 * payload.
 */
@RestController
public class AgentController {

    private static final long TERMINAL_RETENTION_MINUTES = 10;
    /** Server-side ceiling for the /runs list — a caller asking for more gets this. */
    private static final int MAX_RUNS_QUERY_LIMIT = 500;

    private final AgentRunner runner;
    private final AgentRunRegistry registry;
    private final AgentRunPersistenceService persistence;
    private final AiToolRegistry toolRegistry;
    private final WorkflowService workflows;
    private final WorkflowExecutionService workflowExecution;
    private final fan.summer.fengyu.web.StreamTicketService streamTickets;
    private final fan.summer.fengyu.ai.ChatFileGrantService chatFiles;
    private final fan.summer.fengyu.plugin.runtime.PluginFileGrantService files;
    /** Optional: emits a unified host notification when a run terminates (null in tests). */
    private final fan.summer.fengyu.notification.NotificationService notifications;

    /**
     * Per-run SSE sinks. Created on {@code /run} (one sink per run), consumed on the
     * {@code GET /stream} handler. A run that never streams just accumulates events until
     * the registry evicts it; a run whose client connects late replays the buffered events.
     */
    private final Map<String, AgentStreamSink> sinks = new ConcurrentHashMap<>();

    public AgentController(AgentRunner runner, AgentRunRegistry registry,
            AgentRunPersistenceService persistence, AiToolRegistry toolRegistry,
            WorkflowService workflows, WorkflowExecutionService workflowExecution,
            fan.summer.fengyu.web.StreamTicketService streamTickets,
            fan.summer.fengyu.ai.ChatFileGrantService chatFiles,
            fan.summer.fengyu.plugin.runtime.PluginFileGrantService files) {
        this(runner, registry, persistence, toolRegistry, workflows, workflowExecution,
                streamTickets, chatFiles, files, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentController(AgentRunner runner, AgentRunRegistry registry,
            AgentRunPersistenceService persistence, AiToolRegistry toolRegistry,
            WorkflowService workflows, WorkflowExecutionService workflowExecution,
            fan.summer.fengyu.web.StreamTicketService streamTickets,
            fan.summer.fengyu.ai.ChatFileGrantService chatFiles,
            fan.summer.fengyu.plugin.runtime.PluginFileGrantService files,
            fan.summer.fengyu.notification.NotificationService notifications) {
        this.runner = runner;
        this.registry = registry;
        this.persistence = persistence;
        this.toolRegistry = toolRegistry;
        this.workflows = workflows;
        this.workflowExecution = workflowExecution;
        this.streamTickets = streamTickets;
        this.chatFiles = chatFiles;
        this.files = files;
        this.notifications = notifications;
    }

    /**
     * Mints the one-time ticket {@code GET /api/agent/stream} redeems via {@code ?ticket=}
     * (EventSource cannot send the header token; a ticket authorizes exactly one stream
     * connection and never reaches URL logs as the full credential).
     */
    @PostMapping("/api/agent/stream-ticket")
    public Map<String, Object> streamTicket() {
        var issued = streamTickets.issue(fan.summer.fengyu.web.StreamTicketService.AGENT_STREAM_ENDPOINT);
        return Map.of("ticket", issued.ticket(), "expiresAt", issued.expiresAt().toString());
    }

    // ── /run ───────────────────────────────────────────────────────────

    /**
     * Starts a Plan-and-Execute run for the given goal. The run executes on a virtual thread
     * inside {@link AgentRunner}; this method returns immediately with the run id. The caller
     * then opens {@code GET /stream?runId=...} to observe progress.
     */
    @PostMapping("/api/agent/run")
    public Map<String, String> run(@RequestBody AgentRunRequest req) {
        String goal = req.goal() == null ? "" : req.goal();
        AgentRun run = registry.create(goal, req.config(), req.workflow());
        ResolvedRunFiles resolved = resolveRunFiles(req.files());
        run.attachFileRefs(resolved.fileRefs());
        issuedRunFileGrants.put(run.getRunId(), resolved.issuedGrants());
        try {
            return start(run, null);
        } catch (RuntimeException e) {
            // start() only registers the terminal cleanup once it has wired the sink; a failure
            // before that point (e.g. persistence) must not leak the grants minted above.
            revokeRunFileGrants(run.getRunId());
            throw e;
        }
    }

    /**
     * Starts up to eight independent agent runs together. Each child has its own lifecycle,
     * persistence record, approval gates, cancellation flag, and SSE stream; runners execute
     * concurrently on virtual threads.
     */
    /**
     * Starts up to eight independent agent runs together. {@code capabilityMode:"read-only"}
     * restricts every child to read-effect tools — the declared shape for parallel
     * research/review tasks (children cannot spawn further runs, so depth is one by design).
     */
    @PostMapping("/api/agent/batch")
    public Map<String, List<String>> batch(@RequestBody AgentBatchRequest req) {
        List<String> goals = req.goals() == null ? List.of() : req.goals().stream()
                .map(goal -> goal == null ? "" : goal.trim())
                .filter(goal -> !goal.isBlank())
                .toList();
        if (goals.isEmpty() || goals.size() > 8) {
            throw new IllegalArgumentException("Batch requires between 1 and 8 non-empty goals");
        }
        String capability = req.capabilityMode() == null || req.capabilityMode().isBlank()
                ? null : req.capabilityMode().trim().toLowerCase(java.util.Locale.ROOT);
        if (capability != null && !AgentRunConfig.CAPABILITY_READ_ONLY.equals(capability)) {
            throw new IllegalArgumentException("capabilityMode must be '"
                    + AgentRunConfig.CAPABILITY_READ_ONLY + "' (or omitted)");
        }
        List<String> runIds = new ArrayList<>(goals.size());
        for (String goal : goals) {
            AgentRunConfig config = req.config() == null
                    ? new AgentRunConfig(false, true, false, 0) : req.config();
            if (capability != null) config = config.withCapabilityMode(capability);
            AgentRun child = registry.create(goal, config, null);
            runIds.add(start(child, null).get("runId"));
        }
        return Map.of("runIds", List.copyOf(runIds));
    }

    private Map<String, String> start(AgentRun run, String resumedFrom) {
        persistence.create(run, resumedFrom);

        // Create the SSE sink FIRST so events emitted by the runner before the /stream
        // client connects are buffered, not lost.
        AgentStreamSink sink = new AgentStreamSink(run.getRunId(),
                terminalSink -> scheduleCleanup(run.getRunId(), terminalSink));
        sinks.put(run.getRunId(), sink);

        // The run's terminal event also lands in the unified notification center (toast +
        // native desktop notification + history) so a backgrounded run's completion reaches
        // the user even when no stream client is attached. Transparent null for tests.
        fan.summer.fengyu.ai.agent.AgentEventSink notifying = notifications == null
                ? persistence.persisting(run, sink)
                : notifications.forAgentRun(run, persistence.persisting(run, sink));
        runner.run(run, notifying);
        return Map.of("runId", run.getRunId());
    }

    @GetMapping("/api/agent/runs")
    public List<AgentRunPersistenceService.RunSummary> persistedRuns(
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "200") Integer limit) {
        int effective = limit == null ? 200 : Math.max(1, Math.min(limit, MAX_RUNS_QUERY_LIMIT));
        return persistence.search(q, effective);
    }

    @GetMapping("/api/agent/runs/{runId}")
    public AgentRunPersistenceService.RunDetail persistedRun(@PathVariable String runId) {
        return persistence.detail(runId);
    }

    @PostMapping("/api/agent/runs/{runId}/resume")
    public Map<String, String> resume(@PathVariable String runId) {
        AgentRunPersistenceService.ResumeState state = persistence.resumeState(runId);
        AgentRun run = registry.create(
                state.goal(), state.config(), state.plan(), state.completedExecutions());
        return start(run, state.resumedFrom());
    }

    /** Forks a terminal run into a peer copy that executes the same plan from scratch. */
    @PostMapping("/api/agent/runs/{runId}/fork")
    public Map<String, String> fork(@PathVariable String runId) {
        AgentRunPersistenceService.ResumeState state = persistence.forkState(runId);
        AgentRun run = registry.create(
                state.goal(), state.config(), state.plan(), state.completedExecutions());
        return start(run, state.resumedFrom());
    }

    /**
     * Rewinds a terminal run to its first {@code keepSteps} steps and resumes from there.
     * Side effects of the dropped steps are not rolled back — the resumed run pauses for
     * plan review so a human can account for them.
     */
    @PostMapping("/api/agent/runs/{runId}/rewind")
    public Map<String, String> rewind(@PathVariable String runId,
                                      @RequestBody RewindRequest request) {
        AgentRunPersistenceService.ResumeState state =
                persistence.rewindState(runId, request == null ? 0 : request.keepSteps());
        AgentRun run = registry.create(
                state.goal(), state.config(), state.plan(), state.completedExecutions());
        return start(run, state.resumedFrom());
    }

    // ── /stream (SSE) ──────────────────────────────────────────────────

    /**
     * Opens an SSE stream for a run. Buffered events emitted since {@code /run} are replayed
     * first, then live events are pushed as the run progresses. Completes the emitter when
     * the run reaches a terminal state (onComplete / onError / cancellation).
     *
     * @param runId the id returned by {@code /run}
     * @return an {@link SseEmitter}; the caller connects with {@code EventSource}
     */
    @GetMapping(value = "/api/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String runId) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout — runs may pause on approvals
        AgentStreamSink sink = sinks.get(runId);

        if (sink == null) {
            sendAndComplete(emitter, Map.of("message", "Unknown or expired runId: " + runId));
            return emitter;
        }
        sink.attach(emitter);
        return emitter;
    }

    // ── /approve ───────────────────────────────────────────────────────

    /**
     * Releases the run's approval gate (plan or step). If an edited plan body is supplied it
     * replaces the current plan before the gate releases, mirroring
     * {@link AgentRun#approve(AgentPlan)}.
     */
    @PostMapping("/api/agent/{runId}/approve")
    public Map<String, Object> approve(@PathVariable String runId,
                                       @RequestBody(required = false) AgentPlan edited) {
        AgentRun run = registry.get(runId);
        if (run == null) {
            return Map.of("ok", false, "error", "Unknown runId: " + runId);
        }
        run.approve(edited);
        persistence.appendEvent(runId, "approval_resolved",
                Map.of("editedPlan", edited != null));
        return Map.of("ok", true, "runId", runId, "status", run.getStatus().name());
    }

    // ── /cancel ────────────────────────────────────────────────────────

    /**
     * Marks the run cancelled. Honored cooperatively by {@link AgentRunner} before each step
     * and after any approval gate; the run ends {@link fan.summer.fengyu.ai.agent.AgentRunStatus#CANCELLED}.
     */
    @PostMapping("/api/agent/{runId}/cancel")
    public Map<String, Object> cancel(@PathVariable String runId) {
        AgentRun run = registry.get(runId);
        if (run == null) {
            return Map.of("ok", false, "error", "Unknown runId: " + runId);
        }
        run.markCancelled();
        persistence.appendEvent(runId, "cancel_requested", Map.of());
        // Releasing any armed approval gate lets the runner observe the cancellation promptly.
        run.approve(null);
        return Map.of("ok", true, "runId", runId, "status", run.getStatus().name());
    }

    // ── /tools (spec §3.6.1) ───────────────────────────────────────────

    /**
     * Lists the orchestrable tools for the agent UI and the Phase 2 canvas, one entry per
     * currently available tool. Descriptors add stable ownership, output schema, and revision
     * metadata to the input schema Spring AI attaches to every callback.
     */
    @GetMapping("/api/agent/tools")
    public List<AiToolRegistry.ToolDescriptor> tools(
            @org.springframework.web.bind.annotation.RequestHeader(name = "Accept-Language", required = false) String acceptLanguage) {
        return toolRegistry.descriptors(fan.summer.fengyu.plugin.market.ManifestI18n.resolveLocale(acceptLanguage));
    }

    // ── reusable workflows ────────────────────────────────────────────

    @GetMapping("/api/workflows")
    public List<WorkflowDefinition> workflows() {
        return workflows.list();
    }

    @GetMapping("/api/workflows/{workflowId}")
    public WorkflowDefinition workflow(@PathVariable String workflowId) {
        return workflows.get(workflowId);
    }

    @PostMapping("/api/workflows")
    public WorkflowDefinition createWorkflow(@RequestBody WorkflowService.WorkflowDraft draft) {
        return workflows.create(draft);
    }

    @PutMapping("/api/workflows/{workflowId}")
    public WorkflowDefinition updateWorkflow(@PathVariable String workflowId,
                                             @RequestBody WorkflowService.WorkflowDraft draft) {
        return workflows.update(workflowId, draft);
    }

    @PostMapping("/api/workflows/{workflowId}/publish")
    public WorkflowDefinition publishWorkflow(@PathVariable String workflowId,
                                              @RequestBody(required = false) PublishRequest request) {
        return workflows.setPublished(workflowId, request == null || request.published(),
                request == null ? null : request.expectedRevision());
    }

    @GetMapping("/api/workflows/{workflowId}/revisions")
    public List<WorkflowRevisionSummary> workflowRevisions(@PathVariable String workflowId) {
        return workflows.revisions(workflowId);
    }

    @GetMapping("/api/workflows/{workflowId}/revisions/{revision}")
    public WorkflowDefinition workflowRevision(@PathVariable String workflowId,
                                               @PathVariable int revision) {
        return workflows.revision(workflowId, revision);
    }

    @PostMapping("/api/workflows/{workflowId}/revisions/{revision}/restore")
    public WorkflowDefinition restoreWorkflowRevision(
            @PathVariable String workflowId,
            @PathVariable int revision,
            @RequestBody(required = false) RestoreWorkflowRevisionRequest request) {
        return workflows.restore(workflowId, revision,
                request == null ? null : request.expectedRevision());
    }

    @DeleteMapping("/api/workflows/{workflowId}")
    public Map<String, Object> deleteWorkflow(@PathVariable String workflowId) {
        // One transaction cancels durable triggers and deletes the definition, so neither side
        // can survive alone as a permanently failing schedule or a half-deleted workflow.
        fan.summer.fengyu.ai.tasks.BackgroundTaskScheduler.WorkflowDeleteResult deleted =
                taskScheduler.deleteWorkflow(workflowId);
        return Map.of(
                "ok", true,
                "cancelledSchedules", deleted.cancelledSchedules(),
                "cancelledWebhookTriggers", deleted.cancelledWebhookTriggers());
    }

    @PostMapping("/api/workflows/{workflowId}/run")
    public Map<String, String> runWorkflow(@PathVariable String workflowId,
                                          @RequestBody(required = false) WorkflowRunRequest request) {
        Map<String, Object> inputs = request == null || request.inputs() == null
                ? Map.of() : request.inputs();
        AgentRunConfig config = request == null ? null : request.config();
        ResolvedRunFiles resolved = resolveRunFiles(request == null ? null : request.files());
        AgentRun run;
        try {
            run = workflowExecution.createManual(workflowId, inputs, config, resolved.fileRefs());
        } catch (RuntimeException e) {
            // createManual validates + compiles before registering the run — a bad workflowId or
            // inputs must not leak the grants minted by resolveRunFiles.
            revokeIssuedGrants(resolved.issuedGrants());
            throw e;
        }
        issuedRunFileGrants.put(run.getRunId(), resolved.issuedGrants());
        try {
            return start(run, null);
        } catch (RuntimeException e) {
            revokeRunFileGrants(run.getRunId());
            throw e;
        }
    }

    /**
     * Resolves the file-class workflow inputs of one run into per-plugin grants: picker/upload
     * grants minted earlier via {@code /api/ai/files/*} are validated and passed through, a
     * native path is granted now, and {@code createSharedDirectory} mints one host-owned
     * cross-plugin scratch directory. Keyed by input name — the runner exposes them to tool
     * dispatch as {@code @file:<name>} placeholder replacements.
     *
     * <p>{@code issuedGrants} carries ONLY the grants this call minted (native + shared) — the
     * pass-through picker refs stay owned by their original holder — so the run's terminal
     * cleanup can revoke exactly what the run allocated (see {@link #revokeRunFileGrants}).
     */
    private ResolvedRunFiles resolveRunFiles(List<RunFile> runFiles) {
        if (runFiles == null || runFiles.isEmpty()) return ResolvedRunFiles.EMPTY;
        if (chatFiles == null || files == null) {
            throw new IllegalArgumentException("File inputs are not available in this deployment");
        }
        Map<String, List<ChatFileContext.ActiveFileRef>> resolved = new java.util.LinkedHashMap<>();
        List<ChatFileContext.ActiveFileRef> issued = new ArrayList<>();
        try {
            for (RunFile file : runFiles) {
                if (file == null || file.name() == null || !file.name().matches("[A-Za-z0-9_-]{1,64}")) {
                    throw new IllegalArgumentException("Run file input has an invalid name");
                }
                List<ChatFileContext.ActiveFileRef> refs = new ArrayList<>();
                if (Boolean.TRUE.equals(file.createSharedDirectory())) {
                    List<ChatFileContext.ActiveFileRef> shared = chatFiles.grantSharedDirectory();
                    refs.addAll(shared);
                    issued.addAll(shared);
                } else if (file.nativePath() != null && !file.nativePath().isBlank()) {
                    String kind = file.kind() == null ? "file" : file.kind();
                    List<ChatFileContext.ActiveFileRef> nativeRefs = chatFiles.grantNative(
                            file.nativePath(), kind, Boolean.TRUE.equals(file.writableDirectory()));
                    refs.addAll(nativeRefs);
                    issued.addAll(nativeRefs);
                } else if (file.refs() != null && !file.refs().isEmpty()) {
                    for (AiFileController.ActiveFileRefDto dto : file.refs()) {
                        if (dto == null || dto.pluginId() == null || dto.ref() == null) {
                            throw new IllegalArgumentException(
                                    "Run file input '" + file.name() + "' carries an invalid grant");
                        }
                        files.validate(dto.pluginId(), dto.ref());
                        refs.add(new ChatFileContext.ActiveFileRef(dto.pluginId(), dto.ref()));
                    }
                }
                if (refs.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Run file input '" + file.name() + "' resolved to no file grant");
                }
                resolved.put(file.name(), List.copyOf(refs));
            }
        } catch (RuntimeException e) {
            // A later input failing must not leak the grants an earlier input already minted.
            revokeIssuedGrants(issued);
            throw e;
        }
        return new ResolvedRunFiles(Map.copyOf(resolved), List.copyOf(issued));
    }

    /**
     * A run's resolved file inputs plus the grants this controller minted for them. Pass-through
     * picker/upload refs are intentionally absent from {@code issuedGrants}.
     */
    private record ResolvedRunFiles(Map<String, List<ChatFileContext.ActiveFileRef>> fileRefs,
                                    List<ChatFileContext.ActiveFileRef> issuedGrants) {
        static final ResolvedRunFiles EMPTY = new ResolvedRunFiles(Map.of(), List.of());
    }

    // ── background tasks ────────────────────────────────────────────────

    @org.springframework.beans.factory.annotation.Autowired
    private fan.summer.fengyu.ai.tasks.BackgroundTaskRegistry backgroundTasks;

    @org.springframework.beans.factory.annotation.Autowired
    private fan.summer.fengyu.ai.tasks.BackgroundTaskScheduler taskScheduler;

    /** Lists background tasks (workflow runs launched by the model or the UI), newest first. */
    @GetMapping("/api/agent/tasks")
    public List<java.util.Map<String, Object>> backgroundTaskList() {
        return backgroundTasks.list();
    }

    /** Global bounded-queue pressure and the current owner's active share. */
    @GetMapping("/api/agent/tasks/capacity")
    public fan.summer.fengyu.ai.tasks.BackgroundTaskRegistry.Capacity backgroundTaskCapacity() {
        return backgroundTasks.capacity();
    }

    /** One background task's snapshot; {@code timeoutMs} optionally blocks for completion. */
    @GetMapping("/api/agent/tasks/{taskId}")
    public java.util.Map<String, Object> backgroundTask(@PathVariable String taskId,
            @RequestParam(required = false) Long timeoutMs) {
        try {
            java.util.Map<String, Object> snapshot =
                    backgroundTasks.awaitOutput(taskId, timeoutMs == null ? 0 : timeoutMs);
            if (snapshot == null) throw new IllegalArgumentException("Unknown task: " + taskId);
            return snapshot;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for task " + taskId);
        }
    }

    /** Cancels a queued or running background task (cooperative first for running processes). */
    @org.springframework.web.bind.annotation.DeleteMapping("/api/agent/tasks/{taskId}")
    public java.util.Map<String, Object> killBackgroundTask(@PathVariable String taskId) {
        boolean killed = backgroundTasks.kill(taskId);
        return java.util.Map.of("ok", killed, "taskId", taskId);
    }

    // ── workflow schedules ──────────────────────────────────────────────

    @GetMapping("/api/agent/schedules")
    public List<java.util.Map<String, Object>> schedules() {
        return taskScheduler.list();
    }

    /** Creates a recurring (or one-shot delayed) workflow schedule. */
    @PostMapping("/api/agent/schedules")
    public java.util.Map<String, Object> createSchedule(@RequestBody ScheduleRequest request) {
        if (request == null || request.workflowId() == null || request.workflowId().isBlank()) {
            throw new IllegalArgumentException("workflowId is required");
        }
        fan.summer.fengyu.ai.tasks.BackgroundTaskScheduler.Schedule created =
                taskScheduler.create(request.workflowId(), request.inputs(),
                        request.intervalSeconds() == null ? 3600 : request.intervalSeconds(),
                        request.recurring() == null || request.recurring(),
                        Boolean.TRUE.equals(request.fireImmediately()));
        return taskScheduler.summary(created);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/api/agent/schedules/{scheduleId}")
    public java.util.Map<String, Object> deleteSchedule(@PathVariable String scheduleId) {
        return java.util.Map.of("ok", taskScheduler.delete(scheduleId), "scheduleId", scheduleId);
    }

    private void scheduleCleanup(String runId, AgentStreamSink sink) {
        CompletableFuture.delayedExecutor(TERMINAL_RETENTION_MINUTES, TimeUnit.MINUTES)
                .execute(() -> {
                    sinks.remove(runId, sink);
                    registry.remove(runId);
                    revokeRunFileGrants(runId);
                });
    }

    /** Grants this controller minted for a run (native + shared), keyed by run id. Pass-through
     *  picker/upload refs stay with their original holder and are never tracked here. */
    private final java.util.Map<String, List<ChatFileContext.ActiveFileRef>> issuedRunFileGrants =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Revokes the file grants a run allocated at creation time. Called from the terminal
     * cleanup (and the failure paths of the run endpoints) so repeated file-bearing runs cannot
     * exhaust PluginFileGrantService's active-grant cap; revoking the last grant of a shared
     * scratch directory also reclaims its disk tree.
     */
    void revokeRunFileGrants(String runId) {
        revokeIssuedGrants(issuedRunFileGrants.remove(runId));
    }

    private void revokeIssuedGrants(List<ChatFileContext.ActiveFileRef> issued) {
        if (issued == null || files == null) return;
        for (ChatFileContext.ActiveFileRef ref : issued) {
            try {
                files.revoke(ref.pluginId(), ref.ref().id());
            } catch (RuntimeException ignored) {
                // An unknown/already-revoked grant must not block the rest of the cleanup.
            }
        }
    }

    // ── SSE sink + buffering ──────────────────────────────────────────

    /**
     * An {@link AgentEventSink} that buffers every event until an {@link SseEmitter} is
     * attached (by the {@code GET /stream} handler), then forwards live events to it. Each
     * event is sent as a named SSE event whose {@code data} is a small JSON map stamped with
     * a monotonic {@code "seq"} (1-based per run) so a client that re-attaches can skip
     * events it already received.
     *
     * <p>If the attached client dies mid-run (a send fails), the sink detaches and returns
     * to buffering — every event of the dead window is re-buffered (under the same
     * {@link #MAX_BUFFERED_EVENTS} cap) and replayed, in order, to the next client that
     * attaches. The client combines the replay with {@code seq} to skip anything it did
     * already receive before the connection broke.
     *
     * <p>The two terminal events ({@link #onComplete} / {@link #onError}) both complete the
     * emitter, so the {@code EventSource} on the client closes cleanly.
     */
    static final class AgentStreamSink implements AgentEventSink {

        private final Logger log = LoggerFactory.getLogger(AgentStreamSink.class);
        private final String runId;
        private final Consumer<AgentStreamSink> onTerminated;
        private final AtomicBoolean terminationNotified = new AtomicBoolean(false);

        /**
         * Ceiling for pre-attach buffering. A run paused on an approval gate (or one whose
         * client never connects) would otherwise accumulate every event indefinitely; past the
         * cap the OLDEST events are dropped and a {@code buffer_truncated} marker leads the
         * replay so the client knows it joined mid-history.
         */
        static final int MAX_BUFFERED_EVENTS = 2_000;

        /** Buffered events that arrived before the client connected to /stream. */
        private final List<BufferedEvent> buffer = new CopyOnWriteArrayList<>();

        /** Set when the buffer overflowed and oldest events were dropped. */
        private volatile boolean bufferTruncated = false;

        /** The emitter once attached; null until /stream opens. Volatile so the runner's
         *  virtual thread reliably sees the attach from the controller's request thread. */
        private volatile SseEmitter emitter;

        /** True once a send failed — the client is gone, so stop pushing and release the
         *  connection instead of throwing for the rest of the run. Reset by a re-attach. */
        private volatile boolean clientDead = false;

        /** True once the buffered events have been drained to the CURRENT client; a client
         *  death resets it so the dead window re-buffers for the next attach. */
        private volatile boolean drained = false;

        /** Monotonic per-run event counter backing the {@code seq} payload field (1-based). */
        private long seq = 0;

        /**
         * True once the run reached a terminal state (onComplete / onError). Read by
         * {@link #attach(SseEmitter)} after draining: if the terminal event was buffered
         * (because the emitter wasn't attached yet), the late-connecting client has now
         * received it as data, but {@link #complete()} early-returned at termination time —
         * so attach must finish the emitter here or the no-timeout connection leaks.
         */
        private volatile boolean terminated = false;

        /**
         * Non-null when the run terminated via {@link #onError(String)} — if set,
         * {@link #attach(SseEmitter)} completes the emitter <em>with that error</em> rather
         * than normally, matching the live-delivery semantics of {@link #onError(String)}.
         */
        private volatile Throwable terminalError = null;

        AgentStreamSink(String runId) {
            this(runId, ignored -> {});
        }

        AgentStreamSink(String runId, Consumer<AgentStreamSink> onTerminated) {
            this.runId = runId;
            this.onTerminated = onTerminated;
        }

        /** Called by the /stream handler: registers the emitter and replays the buffer. */
        synchronized void attach(SseEmitter emitter) {
            this.emitter = emitter;
            this.clientDead = false;
            emitter.onCompletion(() -> log.debug("agent {}: SSE stream completed", runId));
            emitter.onTimeout(() -> {
                log.debug("agent {}: SSE stream timed out", runId);
                emitter.complete();
            });
            emitter.onError(ex -> log.debug("agent {}: SSE stream error: {}", runId, ex.getMessage()));
            drain();
            // If the run terminated before the client connected, complete() early-returned
            // at termination time (emitter was null). The buffer just delivered the buffered
            // terminal event as data — now finish the emitter so the connection closes.
            if (terminated) {
                if (terminalError != null) {
                    completeWithError();
                } else {
                    complete();
                }
            }
        }

        /** Drains the buffer to the emitter under the lock so new events can't interleave.
         *  If the client dies mid-replay the unsent tail stays buffered (a re-attach drains
         *  again; {@code seq} lets that client skip what it did receive). */
        private synchronized void drain() {
            if (drained) return;
            if (bufferTruncated) {
                send(new BufferedEvent("buffer_truncated", Map.of(
                        "kept", buffer.size(), "cap", MAX_BUFFERED_EVENTS)));
            }
            int sent = 0;
            for (BufferedEvent e : buffer) {
                if (clientDead) break;
                send(e);
                if (clientDead) break;
                sent++;
            }
            buffer.subList(0, sent).clear();
            if (!clientDead && buffer.isEmpty()) drained = true;
        }

        @Override public void onPlanToken(String delta) {
            emit("plan_token", Map.of("delta", delta == null ? "" : delta));
        }

        @Override public void onPlanReady(AgentPlan plan) {
            emit("plan_ready", Map.of(
                    "goal", plan.goal(),
                    "steps", plan.steps() == null ? List.of() : plan.steps(),
                    "reasoning", plan.reasoning() == null ? "" : plan.reasoning()));
        }

        @Override public void onPlanApprovalRequested() {
            emit("plan_approval_requested", Map.of());
        }

        @Override public void onStepStart(int index) {
            emit("step_start", Map.of("index", index));
        }

        @Override public void onStepComplete(int index, String result) {
            emit("step_complete", Map.of("index", index, "result", result == null ? "" : result));
        }

        @Override public void onStepRetry(int index, int nextAttempt, int maxAttempts,
                                          long delayMs, String error) {
            emit("step_retry", Map.of(
                    "index", index,
                    "nextAttempt", nextAttempt,
                    "maxAttempts", maxAttempts,
                    "delayMs", delayMs,
                    "error", error == null ? "" : error));
        }

        @Override public void onStepSkipped(int index) {
            emit("step_skipped", Map.of("index", index));
        }

        @Override public void onStepApprovalRequested(int index) {
            emit("step_approval_requested", Map.of("index", index));
        }

        @Override public void onComplete(String summary) {
            emit("complete", Map.of("summary", summary == null ? "" : summary));
            terminated = true;
            complete();
            notifyTerminated();
        }

        @Override public void onError(String message) {
            emit("error", Map.of("message", message == null ? "" : message));
            terminated = true;
            terminalError = new IllegalStateException(message == null ? "" : message);
            complete();
            notifyTerminated();
        }

        private void notifyTerminated() {
            if (terminationNotified.compareAndSet(false, true)) {
                onTerminated.accept(this);
            }
        }

        /**
         * Routes an event to either the live emitter (if attached and drained) or the buffer
         * (otherwise). Every payload is stamped with the next monotonic {@code seq}. A live
         * send that fails (client died) re-buffers its event — the emitter was detached by
         * {@link #send}, so the dead window replays to the next attach. Synchronized so a
         * late-arriving buffer entry can't be missed during the drain window.
         */
        private synchronized void emit(String event, Object data) {
            BufferedEvent be = new BufferedEvent(event, withSeq(data));
            if (emitter == null || !drained) {
                addToBuffer(be);
                // The emitter may have appeared while we were appending; re-check under the lock
                // so events produced during the drain window still get delivered live.
                if (emitter != null && !drained) {
                    drain();
                }
                return;
            }
            send(be);
            if (clientDead) {
                // The live send failed — the event never reached the client. Keep it for the
                // reconnecting client (seq dedupes it if the send somehow did land).
                addToBuffer(be);
            }
        }

        /** Buffers one event, enforcing the oldest-drop cap with a truncation marker. */
        private void addToBuffer(BufferedEvent be) {
            buffer.add(be);
            if (buffer.size() > MAX_BUFFERED_EVENTS) {
                // Drop the oldest entry — the replay leads with a truncation marker, so
                // a late client knows the beginning of the run is not being delivered.
                buffer.remove(0);
                bufferTruncated = true;
            }
        }

        /** Copies a map payload with this run's next monotonic {@code seq} stamped in
         *  (1-based). Non-map payloads (none today) pass through unchanged. */
        private Object withSeq(Object data) {
            long eventSeq = ++seq;
            if (data instanceof Map<?, ?> map) {
                Map<String, Object> stamped = new java.util.LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    stamped.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                stamped.put("seq", eventSeq);
                return stamped;
            }
            return data;
        }

        /** Sends one buffered event to the live emitter. A failed send means the client is
         *  gone — mark it dead (idempotently), complete the emitter so the container reclaims
         *  the connection, and DETACH: back to buffering, so the dead window's events are
         *  replayed to the next client that attaches. */
        private void send(BufferedEvent be) {
            SseEmitter em = emitter;
            if (em == null || clientDead) return;
            try {
                em.send(SseEmitter.event().name(be.event()).data(be.data(), MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                log.debug("agent {}: SSE send failed ({}): {}", runId, be.event(), e.getMessage());
                clientDead = true;
                try {
                    em.completeWithError(e);
                } catch (Exception ignored) {
                    // Already completed by the container.
                }
                emitter = null;
                drained = false;
            }
        }

        /**
         * Completes the emitter (terminal event). If the run already terminated but no emitter
         * was attached yet, this is a no-op — {@link #attach(SseEmitter)} will finish the
         * emitter after replaying the buffered terminal event.
         */
        private synchronized void complete() {
            SseEmitter em = emitter;
            if (em == null) return;
            try {
                em.complete();
            } catch (Exception e) {
                log.debug("agent {}: SSE complete failed: {}", runId, e.getMessage());
            }
        }

        /**
         * Completes the emitter <em>with an error</em>, mirroring the live-delivery semantics
         * of {@link #onError(String)} for the late-connect case (terminal event was buffered).
         */
        private synchronized void completeWithError() {
            SseEmitter em = emitter;
            if (em == null) return;
            try {
                em.completeWithError(terminalError);
            } catch (Exception e) {
                log.debug("agent {}: SSE completeWithError failed: {}", runId, e.getMessage());
            }
        }

        /** An event captured for (deferred) delivery: a named SSE event + its JSON-able data. */
        private record BufferedEvent(String event, Object data) {}
    }

    // ── small helpers ─────────────────────────────────────────────────

    /** Sends an {@code error} event then completes the emitter (used for the unknown-runId path). */
    private static void sendAndComplete(SseEmitter emitter, Object data) {
        try {
            emitter.send(SseEmitter.event().name("error").data(data, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            // best effort
        }
        emitter.complete();
    }

    // ── DTOs ──────────────────────────────────────────────────────────

    /**
     * {@code POST /api/agent/run} body. When {@code workflow} is supplied it is executed
     * deterministically; otherwise the active AI backend plans a workflow from {@code goal}.
     */
    public record AgentRunRequest(String goal, AgentRunConfig config, AgentPlan workflow,
                                  List<RunFile> files) {}
    /** One file-class workflow input: pass-through grants, a native path, or a shared scratch dir. */
    public record RunFile(String name, List<AiFileController.ActiveFileRefDto> refs,
                          String nativePath, String kind, Boolean writableDirectory,
                          Boolean createSharedDirectory) {}
    public record AgentBatchRequest(List<String> goals, AgentRunConfig config, String capabilityMode) {}
    public record WorkflowRunRequest(Map<String, Object> inputs, AgentRunConfig config,
                                     List<RunFile> files) {}
    public record PublishRequest(boolean published, Integer expectedRevision) {}
    public record RestoreWorkflowRevisionRequest(Integer expectedRevision) {}
    public record RewindRequest(int keepSteps) {}
    public record ScheduleRequest(String workflowId, java.util.Map<String, Object> inputs,
                                  Integer intervalSeconds, Boolean recurring,
                                  Boolean fireImmediately) {}
}
