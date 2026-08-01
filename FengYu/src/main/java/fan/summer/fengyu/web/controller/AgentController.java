package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.agent.AgentEventSink;
import fan.summer.fengyu.ai.agent.AgentPlan;
import fan.summer.fengyu.ai.agent.AgentRun;
import fan.summer.fengyu.ai.agent.AgentRunConfig;
import fan.summer.fengyu.ai.agent.AgentRunPersistenceService;
import fan.summer.fengyu.ai.agent.AgentRunRegistry;
import fan.summer.fengyu.ai.agent.AgentRunner;
import fan.summer.fengyu.ai.config.AiToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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

    private final AgentRunner runner;
    private final AgentRunRegistry registry;
    private final AgentRunPersistenceService persistence;
    private final AiToolRegistry toolRegistry;

    /**
     * Per-run SSE sinks. Created on {@code /run} (one sink per run), consumed by the
     * {@code GET /stream} handler. A run that never streams just accumulates events until
     * the registry evicts it; a run whose client connects late replays the buffered events.
     */
    private final Map<String, AgentStreamSink> sinks = new ConcurrentHashMap<>();

    public AgentController(AgentRunner runner, AgentRunRegistry registry,
            AgentRunPersistenceService persistence, AiToolRegistry toolRegistry) {
        this.runner = runner;
        this.registry = registry;
        this.persistence = persistence;
        this.toolRegistry = toolRegistry;
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
        return start(run, null);
    }

    /**
     * Starts up to eight independent agent runs together. Each child has its own lifecycle,
     * persistence record, approval gates, cancellation flag, and SSE stream; runners execute
     * concurrently on virtual threads.
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
        List<String> runIds = new ArrayList<>(goals.size());
        for (String goal : goals) {
            AgentRun child = registry.create(goal, req.config(), null);
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

        runner.run(run, persistence.persisting(run, sink));
        return Map.of("runId", run.getRunId());
    }

    @GetMapping("/api/agent/runs")
    public List<AgentRunPersistenceService.RunSummary> persistedRuns() {
        return persistence.list();
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
    public List<AiToolRegistry.ToolDescriptor> tools() {
        return toolRegistry.descriptors();
    }

    private void scheduleCleanup(String runId, AgentStreamSink sink) {
        CompletableFuture.delayedExecutor(TERMINAL_RETENTION_MINUTES, TimeUnit.MINUTES)
                .execute(() -> {
                    sinks.remove(runId, sink);
                    registry.remove(runId);
                });
    }

    // ── SSE sink + buffering ──────────────────────────────────────────

    /**
     * An {@link AgentEventSink} that buffers every event until an {@link SseEmitter} is
     * attached (by the {@code GET /stream} handler), then forwards live events to it. Each
     * event is sent as a named SSE event whose {@code data} is a small JSON map.
     *
     * <p>The two terminal events ({@link #onComplete} / {@link #onError}) both complete the
     * emitter, so the {@code EventSource} on the client closes cleanly.
     */
    static final class AgentStreamSink implements AgentEventSink {

        private final Logger log = LoggerFactory.getLogger(AgentStreamSink.class);
        private final String runId;
        private final Consumer<AgentStreamSink> onTerminated;
        private final AtomicBoolean terminationNotified = new AtomicBoolean(false);

        /** Buffered events that arrived before the client connected to /stream. */
        private final List<BufferedEvent> buffer = new CopyOnWriteArrayList<>();

        /** The emitter once attached; null until /stream opens. Volatile so the runner's
         *  virtual thread reliably sees the attach from the controller's request thread. */
        private volatile SseEmitter emitter;

        /** True once the buffered events have been drained (so we only drain once). */
        private volatile boolean drained = false;

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

        /** Drains the buffer to the emitter under the lock so new events can't interleave. */
        private synchronized void drain() {
            if (drained) return;
            for (BufferedEvent e : buffer) {
                send(e);
            }
            buffer.clear();
            drained = true;
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
         * (otherwise). Once drained, {@code send} is called directly. Synchronized so a
         * late-arriving buffer entry can't be missed during the drain window.
         */
        private synchronized void emit(String event, Object data) {
            BufferedEvent be = new BufferedEvent(event, data);
            if (emitter == null || !drained) {
                buffer.add(be);
                // The emitter may have appeared while we were appending; re-check under the lock
                // so events produced during the drain window still get delivered live.
                if (emitter != null && !drained) {
                    drain();
                }
                return;
            }
            send(be);
        }

        /** Sends one buffered event to the live emitter; swallows transport errors. */
        private void send(BufferedEvent be) {
            SseEmitter em = emitter;
            if (em == null) return;
            try {
                em.send(SseEmitter.event().name(be.event()).data(be.data(), MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                log.debug("agent {}: SSE send failed ({}): {}", runId, be.event(), e.getMessage());
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
    public record AgentRunRequest(String goal, AgentRunConfig config, AgentPlan workflow) {}
    public record AgentBatchRequest(List<String> goals, AgentRunConfig config) {}
}
