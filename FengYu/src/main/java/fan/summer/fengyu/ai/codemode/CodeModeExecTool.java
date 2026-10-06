package fan.summer.fengyu.ai.codemode;

import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.ai.AiConfigService;
import fan.summer.fengyu.ai.FengYuTool;
import fan.summer.fengyu.ai.tools.AiPermissionContext;
import fan.summer.fengyu.ai.tools.ChatToolApprovalGate;
import fan.summer.fengyu.ai.tools.ToolApprovalContext;
import fan.summer.fengyu.ai.tools.ToolApprovalPolicy;
import fan.summer.fengyu.ai.workspace.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Code mode's model-facing tools: {@code exec} (run JavaScript that orchestrates the
 * ordinary tools as async functions) and {@code wait} (resume a still-running cell).
 * The cell runtime is {@link GraalJsCodeModeSession}; the NESTED tool pipeline is the
 * ordinary one — approval via the shared {@link ChatToolApprovalGate} through the
 * {@link ToolApprovalContext} bridge (code mode is never an approval bypass), execution
 * through the real registered callbacks (a nested {@code workspace_exec} fences itself
 * inside the sandbox exactly like a model-issued call — no duplicated sandboxing).
 * {@code exec} may not call itself (registry filters it out of the nested set).
 */
@Component
public class CodeModeExecTool implements FengYuTool, fan.summer.fengyu.ai.tools.ToolEffectProvider {

    private static final Logger log = LoggerFactory.getLogger(CodeModeExecTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Token budget for direct exec/wait results; ≈4 chars per token. */
    static final int MAX_OUTPUT_CHARS = 40_000;

    public static final String EXEC_NAME = "exec";
    public static final String WAIT_NAME = "wait";

    /** Sessions per conversation (the store domain); ad-hoc key for unbound flows. */
    private static final Map<String, GraalJsCodeModeSession> SESSIONS = new ConcurrentHashMap<>();
    /** Live cells grouped by session key — the turn-interrupt termination index. */
    private static final Map<String, Map<String, GraalJsCodeModeSession.CellHandle>> CELLS_BY_SESSION =
            new ConcurrentHashMap<>();

    /** The session key convention, shared with the loop driver's cancel path. */
    public static String sessionKeyFor(Long conversationId) {
        return conversationId != null ? "conversation-" + conversationId : "flow";
    }

    /**
     * Turn-interrupt termination (codex {@code interrupt_active_cells}): the loop driver's
     * cancel path calls this — every still-running cell of the session gets the Terminate
     * command plus the forced context close (double assurance), so a cancelled turn never
     * leaks cell threads to their natural end. Cells that already finished are absent.
     *
     * @return how many cells were terminated.
     */
    public static int terminateActiveCellsFor(Long conversationId) {
        Map<String, GraalJsCodeModeSession.CellHandle> cells =
                CELLS_BY_SESSION.remove(sessionKeyFor(conversationId));
        if (cells == null || cells.isEmpty()) return 0;
        for (GraalJsCodeModeSession.CellHandle handle : cells.values()) {
            handle.terminate();
        }
        log.info("turn interrupt terminated {} active code-mode cell(s)", cells.size());
        return cells.size();
    }

    /** Test visibility: the live cell ids of one session. */
    public static java.util.Set<String> liveCellIds(Long conversationId) {
        Map<String, GraalJsCodeModeSession.CellHandle> cells =
                CELLS_BY_SESSION.get(sessionKeyFor(conversationId));
        return cells == null ? java.util.Set.of() : java.util.Set.copyOf(cells.keySet());
    }

    /** The attached tool callbacks the nested set is built from (host wiring). */
    private final Supplier<List<ToolCallback>> attachedTools;

    /** Shared virtual-thread executor for nested calls (cheap, daemon, never closed). */
    private static final java.util.concurrent.ExecutorService NESTED_EXECUTOR =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    @org.springframework.beans.factory.annotation.Autowired
    public CodeModeExecTool(
            org.springframework.beans.factory.ObjectProvider<fan.summer.fengyu.ai.config.AiToolRegistry> registry) {
        // Deferred lookup: the registry itself consumes List<FengYuTool>, which contains
        // this bean — a constructor dependency would be a creation cycle.
        this.attachedTools = () -> {
            fan.summer.fengyu.ai.config.AiToolRegistry resolved = registry.getIfAvailable();
            return resolved != null ? resolved.callbacks() : List.<ToolCallback>of();
        };
    }

    /** Test/plain construction: an empty nested-tool set. */
    public CodeModeExecTool() {
        this.attachedTools = List::of;
    }

    /** Test construction: an explicit nested-tool supplier. */
    public CodeModeExecTool(Supplier<List<ToolCallback>> attachedTools) {
        this.attachedTools = attachedTools;
    }

    /** The surface gate as a seam (the production check is static; tests force it on). */
    protected boolean surfaceOn() {
        return surfaceActive();
    }

    @Override
    public fan.summer.fengyu.ai.tools.ToolEffect effectFor(String toolName) {
        return switch (toolName == null ? "" : toolName) {
            case EXEC_NAME -> fan.summer.fengyu.ai.tools.ToolEffect.COMMAND;
            case WAIT_NAME -> fan.summer.fengyu.ai.tools.ToolEffect.READ;
            default -> null;
        };
    }

    /** Code mode joins the surface only when enabled AND a workspace is bound. */
    public static boolean surfaceActive() {
        return AiConfigService.getAiCodeModeEnabled() && WorkspaceContext.isBound();
    }

    @Tool(name = EXEC_NAME, description = "PLACEHOLDER — built dynamically; see descriptionFor()")
    public String exec(
            @ToolParam(description = "JavaScript source (raw text, not JSON). See the tool "
                    + "description for the helpers, the tools object, and the optional "
                    + "// @exec: pragma.") String source) {
        if (!surfaceOn()) {
            return "Code mode is not active for this conversation.";
        }
        if (source == null || source.isBlank()) {
            return "The exec source must not be blank.";
        }
        ExecPragma.ExecSource parsed;
        try {
            parsed = ExecPragma.parse(source);
        } catch (IllegalArgumentException e) {
            return "Script failed\nScript error:\n" + e.getMessage();
        }

        // The yield window is clamped (1s floor, 60s ceiling): a hostile pragma must not
        // park the exec call for hours; the token budget converts to a char budget (≈4
        // chars/token) and is consumed by the result formatter below.
        long yieldWindow = parsed.pragma() == null || parsed.pragma().yieldTimeMs() == null
                ? CodeModeToolDescription.DEFAULT_YIELD_TIME_MS
                : Math.max(1_000, Math.min(60_000, parsed.pragma().yieldTimeMs()));
        int outputBudgetChars = parsed.pragma() == null || parsed.pragma().maxOutputTokens() == null
                ? MAX_OUTPUT_CHARS
                : (int) Math.min(Integer.MAX_VALUE,
                        Math.max(1_000, parsed.pragma().maxOutputTokens() * 4L));
        CodeModeProtocol.ExecuteRequest request = new CodeModeProtocol.ExecuteRequest(
                "exec", nestedToolDefinitions(), parsed.source(), yieldWindow, null);

        GraalJsCodeModeSession session = sessionFor(request);
        // Same-cell effect grouping (the ToolBatchExecutor semantics, per exec): READ
        // calls share the cell concurrently, any WRITE/COMMAND/EXTERNAL call runs alone.
        java.util.concurrent.locks.ReadWriteLock effectLock =
                new java.util.concurrent.locks.ReentrantReadWriteLock();
        java.util.concurrent.atomic.AtomicReference<String> cellIdRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        GraalJsCodeModeSession.CellHandle handle =
                session.execute(request, call -> resolveNestedCall(call, effectLock, cellIdRef));
        cellIdRef.set(handle.cellId());
        CELLS_BY_SESSION.computeIfAbsent(sessionKeyFor(currentConversationId()), ignored ->
                new ConcurrentHashMap<>()).put(handle.cellId(), handle);

        CodeModeProtocol.RuntimeResponse first;
        try {
            first = handle.first().get(yieldWindow + 5_000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // The runtime never answered inside the grace window — terminate the cell
            // instead of leaking a phantom in CELLS_BY_SESSION: a wedged handle would
            // otherwise stay "live" for the conversation (terminateActiveCellsFor would
            // "kill" a dead cell, and liveCellIds would report it forever).
            handle.terminate();
            discardCell(currentConversationId(), handle.cellId());
            return "Script failed\nScript error:\n" + e.getMessage();
        }
        // A terminal response closes the cell — only a live yield stays registered for
        // the wait()/terminate() flow.
        if (!(first instanceof CodeModeProtocol.Yielded)) {
            discardCell(currentConversationId(), handle.cellId());
        }
        return format(first, outputBudgetChars);
    }

    /** Removes one cell from its conversation's live registry (no-op when absent). */
    private static void discardCell(Long conversationId, String cellId) {
        Map<String, GraalJsCodeModeSession.CellHandle> sessionCells =
                CELLS_BY_SESSION.get(sessionKeyFor(conversationId));
        if (sessionCells != null) sessionCells.remove(cellId);
    }

    @Tool(name = WAIT_NAME, description = "Resume a still-running exec cell: wait for more "
            + "output, or terminate it.")
    public String wait(
            @ToolParam(description = "The cell ID returned by exec.") String cellId,
            @ToolParam(required = false,
                       description = "How long to wait for new output before yielding again "
                              + "(ms, default 10000).") Integer yieldTimeMs,
            @ToolParam(required = false,
                       description = "Terminate the running cell (default false).") Boolean terminate) {
        if (!surfaceOn()) {
            return "Code mode is not active for this conversation.";
        }
        Map<String, GraalJsCodeModeSession.CellHandle> sessionCells =
                CELLS_BY_SESSION.get(sessionKeyFor(currentConversationId()));
        GraalJsCodeModeSession.CellHandle handle =
                cellId == null || sessionCells == null ? null : sessionCells.get(cellId);
        if (handle == null) {
            return "Unknown cell ID: " + cellId + " (cells close when they finish)";
        }
        if (Boolean.TRUE.equals(terminate)) {
            handle.terminate();
            sessionCells.remove(cellId);
            return "Script terminated";
        }
        long window = yieldTimeMs == null
                ? CodeModeToolDescription.DEFAULT_YIELD_TIME_MS : Math.max(0, yieldTimeMs);
        CodeModeProtocol.RuntimeResponse next;
        try {
            next = handle.poll(window);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "wait interrupted";
        }
        if (next == null) {
            return "Script running with cell ID " + cellId + " — no new output yet; "
                    + "wait again or terminate.";
        }
        if (next instanceof CodeModeProtocol.Result || next instanceof CodeModeProtocol.Terminated) {
            sessionCells.remove(cellId);
        }
        return format(next);
    }

    /** The dynamic exec description with the current nested-tool declaration. */
    public static String descriptionFor(List<ToolCallback> attached) {
        List<CodeModeToolDescription.NestedTool> nested = new ArrayList<>();
        for (ToolCallback callback : attached) {
            ToolDefinition definition = callback.getToolDefinition();
            if (definition == null || definition.name() == null) continue;
            if (EXEC_NAME.equals(definition.name()) || WAIT_NAME.equals(definition.name())) {
                continue;   // exec may not call itself; wait is not script-callable either
            }
            nested.add(new CodeModeToolDescription.NestedTool(
                    definition.name(), definition.description(), definition.inputSchema()));
        }
        return CodeModeToolDescription.execDescription(nested,
                CodeModeToolDescription.DEFAULT_YIELD_TIME_MS);
    }

    private List<CodeModeProtocol.ToolDefinition> nestedToolDefinitions() {
        List<CodeModeProtocol.ToolDefinition> nested = new ArrayList<>();
        for (ToolCallback callback : attachedTools.get()) {
            ToolDefinition definition = callback.getToolDefinition();
            if (definition == null || definition.name() == null) continue;
            if (EXEC_NAME.equals(definition.name()) || WAIT_NAME.equals(definition.name())) continue;
            nested.add(new CodeModeProtocol.ToolDefinition(
                    definition.name(), definition.description(), definition.inputSchema()));
        }
        return nested;
    }

    // ── the nested pipeline (approval + execution — the ordinary one) ──────────────────

    /**
     * One nested call: the shared approval gate first (when the policy flags this
     * invocation), then the real callback on its own virtual thread. Rejections and
     * errors become tool RESULTS the script can catch — never cell crashes.
     */
    private CompletableFuture<CodeModeProtocol.NestedToolResult> resolveNestedCall(
            CodeModeProtocol.NestedToolCall call,
            java.util.concurrent.locks.ReadWriteLock effectLock,
            java.util.concurrent.atomic.AtomicReference<String> cellIdRef) {
        return CompletableFuture.supplyAsync(() -> {
            ToolCallback callback = findCallback(call.toolName());
            if (callback == null) {
                return new CodeModeProtocol.NestedToolResult(call.id(),
                        "No tool named '" + call.toolName() + "' is available in this exec",
                        false);
            }
            try {
                ChatToolApprovalGate.Decision decision = approvalFor(callback, call);
                if (decision != null && !decision.approved()) {
                    String feedback = decision.feedback() == null || decision.feedback().isBlank()
                            ? "the user rejected this nested tool call"
                            : decision.feedback();
                    return new CodeModeProtocol.NestedToolResult(call.id(),
                            "nested call rejected: " + feedback, false);
                }
            } catch (RuntimeException approvalFailure) {
                return new CodeModeProtocol.NestedToolResult(call.id(),
                        "nested approval failed: " + approvalFailure.getMessage(), false);
            }
            boolean readEffect = callback instanceof fan.summer.fengyu.ai.tools.AuditedToolCallback audited
                    && audited.effect() == fan.summer.fengyu.ai.tools.ToolEffect.READ;
            fan.summer.fengyu.ai.session.AiRolloutService.Recorder rolloutRecorder =
                    ToolApprovalContext.rollout();
            try {
                // Approvals happened above, in issue order; the lock schedules execution:
                // concurrent READs, exclusive everything else (same-cell grouping).
                if (readEffect) {
                    effectLock.readLock().lock();
                } else {
                    effectLock.writeLock().lock();
                }
                try {
                    CodeModeProtocol.NestedToolResult result = new CodeModeProtocol.NestedToolResult(
                            call.id(), callback.call(call.argumentsJson()), true);
                    recordNestedResult(rolloutRecorder, call, result, cellIdRef.get());
                    return result;
                } finally {
                    if (readEffect) {
                        effectLock.readLock().unlock();
                    } else {
                        effectLock.writeLock().unlock();
                    }
                }
            } catch (Exception e) {
                CodeModeProtocol.NestedToolResult failure = new CodeModeProtocol.NestedToolResult(
                        call.id(),
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                        false);
                recordNestedResult(rolloutRecorder, call, failure, cellIdRef.get());
                return failure;
            }
        }, NESTED_EXECUTOR);
    }

    /** A nested call's result lands in the turn's rollout log with its cell marker (P1-8). */
    private static void recordNestedResult(fan.summer.fengyu.ai.session.AiRolloutService.Recorder recorder,
            CodeModeProtocol.NestedToolCall call, CodeModeProtocol.NestedToolResult result,
            String cellId) {
        if (recorder == null) return;
        try {
            recorder.toolResult(call.id(), call.toolName(), result.output(), result.success(),
                    null, cellId);
        } catch (Exception ignored) {
            // The log must never break a nested call.
        }
    }

    /**
     * The gate's decision for a nested call; null when it auto-runs. The layered guard
     * runs FIRST (deny rejects outright, allow suppresses the prompt — the same order
     * {@code awaitRequiredApprovals} applies to model-issued batches, P1-7), then the
     * approval policy on the RAW arguments JSON — the same shape a model-issued call
     * would carry, so the readonly whitelist and the dangerous-command patterns see the
     * real {@code command} (an envelope here blinded both, P0-1).
     */
    private ChatToolApprovalGate.Decision approvalFor(ToolCallback callback,
            CodeModeProtocol.NestedToolCall call) {
        ChatToolApprovalGate gate = ToolApprovalContext.gate();
        fan.summer.fengyu.ai.AiStreamCallback stream = ToolApprovalContext.callback();
        if (gate != null) {
            fan.summer.fengyu.ai.tools.ToolGuardService.GuardDecision guardDecision =
                    gate.guardDecision(call.toolName(), callback, call.argumentsJson());
            if (guardDecision != null) {
                if (guardDecision.verdict()
                        == fan.summer.fengyu.ai.tools.ToolGuardService.Verdict.DENY) {
                    return ChatToolApprovalGate.Decision.rejectOnce(guardDecision.reason());
                }
                if (guardDecision.verdict()
                        == fan.summer.fengyu.ai.tools.ToolGuardService.Verdict.ALLOW) {
                    return null;
                }
            }
        }
        if (!ToolApprovalPolicy.requiresApproval(callback, AiPermissionContext.current(),
                call.argumentsJson())) {
            return null;
        }
        if (gate == null || stream == null) {
            // No approval surface (unwired context): fail closed, never bypass.
            return ChatToolApprovalGate.Decision.rejectOnce(
                    "approval required but no approval surface is available");
        }
        return gate.awaitSingleApproval(call.toolName(), call.argumentsJson(), stream);
    }

    private ToolCallback findCallback(String name) {
        for (ToolCallback callback : attachedTools.get()) {
            ToolDefinition definition = callback.getToolDefinition();
            if (definition != null && name.equals(definition.name())) return callback;
        }
        return null;
    }

    // ── sessions + result formatting ───────────────────────────────────────────────────

    private static GraalJsCodeModeSession sessionFor(CodeModeProtocol.ExecuteRequest request) {
        String key = sessionKey();
        return SESSIONS.computeIfAbsent(key, ignored -> new GraalJsCodeModeSession());
    }

    private static String sessionKey() {
        return sessionKeyFor(currentConversationId());
    }

    private static Long currentConversationId() {
        return fan.summer.fengyu.ai.tools.ConversationContext.current();
    }

    /** codex mod.rs output templates with the default budget applied. */
    static String format(CodeModeProtocol.RuntimeResponse response) {
        return format(response, MAX_OUTPUT_CHARS);
    }

    /** codex mod.rs output templates with the caller's char budget applied. */
    static String format(CodeModeProtocol.RuntimeResponse response, int budgetChars) {
        String body = "";
        if (response instanceof CodeModeProtocol.Yielded yielded) {
            body = "Script running with cell ID " + yielded.cellId() + "\n"
                    + itemsToText(yielded.contentItems());
        } else if (response instanceof CodeModeProtocol.Result result) {
            if (result.errorText() != null) {
                body = "Script failed\nScript error:\n" + result.errorText() + "\n"
                        + itemsToText(result.contentItems());
            } else {
                body = "Script completed\n" + itemsToText(result.contentItems());
            }
        } else if (response instanceof CodeModeProtocol.Terminated terminated) {
            body = "Script terminated";
        }
        return body.length() > budgetChars
                ? body.substring(0, budgetChars) + "\n…[output truncated]"
                : body;
    }

    private static String itemsToText(List<CodeModeProtocol.ContentItem> items) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (CodeModeProtocol.ContentItem item : items) {
            if (item instanceof CodeModeProtocol.Text text) {
                out.append(text.text()).append('\n');
            } else if (item instanceof CodeModeProtocol.Image image) {
                out.append("[image: ").append(image.dataUri().length()).append(" bytes]\n");
            }
        }
        return out.toString();
    }
}
