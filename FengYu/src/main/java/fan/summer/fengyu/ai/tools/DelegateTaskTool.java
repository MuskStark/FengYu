package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.ai.FengYuTool;
import fan.summer.fengyu.ai.workspace.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code delegate_task} — the general-purpose subagent: dispatches a self-contained task
 * WITH write access to a nested model loop that has its own conversation context, and
 * returns only the final report (terminal coding-agent practice — the outer transcript
 * keeps the conclusions, not the working steps).
 *
 * <p>Permission boundary: the delegation itself is a WRITE-effect call, so the ordinary
 * approval card governs who may start it; inside, the subagent may only use the workspace
 * tool family (a requested tool set can narrow that, never widen it — no browser,
 * computer, web, or global command tools), the nested loop runs behind the SAME approval
 * gate with the same permission semantics as the outer turn (unverifiable or dangerous
 * commands still surface a card — a delegation is never an approval bypass), and
 * everything it does is checkpointed like main-loop work. {@code isolate=true}
 * additionally runs the whole subagent in a fresh
 * git worktree on a {@code fengyu/task-*} branch so it cannot touch the user's
 * uncommitted changes; a changed worktree is kept and reported for an explicit merge, a
 * clean one is removed. Concurrency is bounded by a semaphore and every delegation has a
 * wall-clock timeout with cancel propagation into the sub-loop.</p>
 */
@Component
public class DelegateTaskTool implements FengYuTool, ToolEffectProvider {

    private static final Logger log = LoggerFactory.getLogger(DelegateTaskTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final int MAX_CONCURRENT_SUBAGENTS = 3;
    static final long DEFAULT_TIMEOUT_SECONDS = 300;
    static final long MAX_TIMEOUT_SECONDS = 900;
    static final long SLOT_WAIT_SECONDS = 10;
    static final long CANCEL_GRACE_SECONDS = 5;

    /** The subagent's whole tool universe — delegation may only narrow this set. */
    static final List<String> ALLOWED_TOOLS = List.of(
            "read_file", "grep", "glob", "write_file", "edit_file", "apply_patch",
            "workspace_exec", "write_stdin");

    static final String TASK_PERSONA = """
            You are a task subagent inside a coding workspace, dispatched by a supervising agent.
            Complete the assigned task autonomously with the attached workspace tools — you may
            read, search, edit, and run project commands. Constraints:
            - Work ONLY on the assigned task; do not refactor or fix unrelated code.
            - Keep changes minimal; follow the project's existing style and its AGENTS.md when present.
            - Verify your work with the narrowest sufficient check (a compile or a focused test).
            - You cannot ask questions: if blocked, record the blocker and finish with what you did.
            Finish with a concise plain-text report:
            - what you changed, citing files as path:line,
            - what you verified and how (commands + outcomes),
            - anything left open and why.""";

    /** The blocking sub-loop behind the tool — swapped for fakes in tests. */
    interface SubagentRunner {
        record Spec(String systemPrompt, String userPrompt, List<ToolCallback> tools,
                WorkspaceContext.Binding workspace) {}

        record Result(String report, int completionTokens) {}

        Result run(Spec spec) throws Exception;

        /** Best-effort stop of the in-flight sub-loop (timeout / outer cancel path). */
        void cancel();
    }

    private final WorkspaceFileTools fileTools;
    private final WorkspaceExecTool execTool;
    private final ApplyPatchTool applyPatchTool;
    private final SubagentRunner runner;
    private final Semaphore slots;
    private final int slotCount;
    private final long slotWaitSeconds;

    @Autowired
    public DelegateTaskTool(WorkspaceFileTools fileTools, WorkspaceExecTool execTool,
            ApplyPatchTool applyPatchTool, ChatToolApprovalGate approvalGate) {
        this(fileTools, execTool, applyPatchTool,
                new CloudSubagentRunner(approvalGate), MAX_CONCURRENT_SUBAGENTS, SLOT_WAIT_SECONDS);
    }

    /** Test constructor: inject the runner seam, a tighter slot count, and a short slot wait. */
    DelegateTaskTool(WorkspaceFileTools fileTools, WorkspaceExecTool execTool,
            ApplyPatchTool applyPatchTool, SubagentRunner runner, int slots,
            long slotWaitSeconds) {
        this.fileTools = fileTools;
        this.execTool = execTool;
        this.applyPatchTool = applyPatchTool;
        this.runner = runner;
        this.slots = new Semaphore(slots);
        this.slotCount = slots;
        this.slotWaitSeconds = slotWaitSeconds;
    }

    @Override
    public ToolEffect effectFor(String toolName) {
        return "delegate_task".equals(toolName) ? ToolEffect.WRITE : null;
    }

    // ── delegate_task ────────────────────────────────────────────────────────────────────

    @Tool(name = "delegate_task",
          description = "Delegate a self-contained task to a subagent that has its own "
                  + "conversation and MAY WRITE (edit files, run project commands) — use it for "
                  + "large mechanical work whose many steps would flood this conversation; the "
                  + "outer turn sees only the final report. The subagent can use the workspace "
                  + "tools (read_file/grep/glob/write_file/edit_file/apply_patch/"
                  + "workspace_exec/write_stdin); the tools parameter may NARROW that set but "
                  + "never widen it. Set isolate=true to run it in a separate git worktree when "
                  + "it must not touch the current uncommitted changes — its changes then land "
                  + "on a fengyu/task-* branch that is reported for an explicit merge. One "
                  + "approval card starts the delegation; inside, command calls the "
                  + "permission policy flags still surface the ordinary approval card.")
    public String delegateTask(
            @ToolParam(description = "The task, specific and self-contained — the subagent "
                    + "cannot ask questions.")
            String task,
            @ToolParam(required = false,
                       description = "Tool names the subagent may use, comma-separated or a "
                              + "JSON array (default: the full workspace set above; unknown or "
                              + "non-workspace names are rejected).")
            String tools,
            @ToolParam(required = false,
                       description = "Wall-clock budget in seconds (default 300, max 900).")
            Integer timeoutSeconds,
            @ToolParam(required = false,
                       description = "Run in an isolated git worktree (default false: work "
                              + "directly in the workspace).")
            Boolean isolate) {
        WorkspaceContext.Binding binding = WorkspaceContext.current();
        if (binding == null) {
            return error("This conversation has no workspace attached");
        }
        if (task == null || task.isBlank()) return error("task must not be blank");

        List<String> requestedTools;
        try {
            requestedTools = resolveToolSet(tools);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
        long timeout = timeoutSeconds == null ? DEFAULT_TIMEOUT_SECONDS
                : Math.max(1, Math.min(timeoutSeconds, (int) MAX_TIMEOUT_SECONDS));

        WorktreeIsolation.Worktree worktree = null;
        WorkspaceContext.Binding effectiveBinding = binding;
        try {
            if (!slots.tryAcquire(slotWaitSeconds, TimeUnit.SECONDS)) {
                return error("All " + slotCount + " subagent slots are busy; "
                        + "retry after the running delegations finish");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error("delegate_task interrupted while waiting for a slot");
        }

        long startNanos = System.nanoTime();
        Thread worker = null;
        try {
            if (Boolean.TRUE.equals(isolate)) {
                try {
                    worktree = WorktreeIsolation.create(binding.root());
                    effectiveBinding = new WorkspaceContext.Binding(
                            worktree.root(), binding.conversationId());
                    // The sub-loop (and every tool it runs) must see the WORKTREE as its
                    // workspace: swap the inheritable binding before the runner thread is
                    // created; the finally below restores the original.
                    WorkspaceContext.set(effectiveBinding);
                } catch (Exception noWorktree) {
                    return error("workspace isolation failed: " + noWorktree.getMessage());
                }
            }

            List<ToolCallback> attached = toolCallbacksFor(requestedTools);
            final WorkspaceContext.Binding subagentBinding = effectiveBinding;
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<SubagentRunner.Result> result = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            worker = Thread.ofVirtual().start(() -> {
                try {
                    result.set(runner.run(new SubagentRunner.Spec(
                            TASK_PERSONA, task.strip(), attached, subagentBinding)));
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    done.countDown();
                }
            });
            if (!done.await(timeout, TimeUnit.SECONDS)) {
                runner.cancel();
                done.await(CANCEL_GRACE_SECONDS, TimeUnit.SECONDS);
                // The timeout envelope carries the worktree report the old error text
                // promised but never delivered — with changes (or a still-live writer)
                // the worktree is KEPT and the user must decide the merge.
                Map<String, Object> timeoutEnvelope = new LinkedHashMap<>();
                timeoutEnvelope.put("success", false);
                timeoutEnvelope.put("error", "delegate_task timed out after " + timeout
                        + "s; the subagent was cancelled");
                if (worktree != null) {
                    Map<String, Object> isolation = isolationReport(worktree);
                    if (worker.isAlive()) {
                        isolation.put("kept", true);
                        isolation.put("note", "the cancelled subagent may still be finishing "
                                + "writes; merge or delete this worktree manually");
                    }
                    timeoutEnvelope.put("isolation", isolation);
                }
                return toJson(timeoutEnvelope);
            }
            Throwable runFailure = failure.get();
            if (runFailure != null) {
                log.debug("delegated subagent failed: {}", runFailure.toString());
                return error("subagent failed: " + (runFailure.getMessage() == null
                        ? runFailure.toString() : runFailure.getMessage()));
            }
            SubagentRunner.Result run = result.get();
            String report = run == null || run.report() == null ? "" : run.report().strip();

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("success", !report.isEmpty());
            envelope.put("report", report.isEmpty()
                    ? "(the subagent finished without a report)" : report);
            envelope.put("seconds", (System.nanoTime() - startNanos) / 1_000_000_000L);
            if (run != null) envelope.put("completionTokens", run.completionTokens());
            if (worktree != null) {
                envelope.put("isolation", isolationReport(worktree));
            }
            return toJson(envelope);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            runner.cancel();
            return error("delegate_task interrupted; the subagent was cancelled");
        } catch (RuntimeException e) {
            return error(e.getMessage());
        } finally {
            if (worktree != null) cleanupWorktree(worktree, worker != null && worker.isAlive());
            if (WorkspaceContext.current() != binding) WorkspaceContext.set(binding);
            slots.release();
        }
    }

    // ── tool-set boundary ────────────────────────────────────────────────────────────────

    /** Parses the requested set (comma list or JSON array); may only narrow ALLOWED_TOOLS. */
    static List<String> resolveToolSet(String tools) {
        Set<String> allowed = new LinkedHashSet<>(ALLOWED_TOOLS);
        if (tools == null || tools.isBlank()) return ALLOWED_TOOLS;

        List<String> requested = new ArrayList<>();
        String trimmed = tools.strip();
        if (trimmed.startsWith("[")) {
            try {
                requested = JSON.readValue(trimmed, new TypeReference<List<String>>() {});
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException(
                        "tools must be a comma-separated list or a JSON array of names");
            }
        } else {
            for (String name : trimmed.split("[,\\s]+")) {
                if (!name.isBlank()) requested.add(name.strip());
            }
        }
        if (requested.isEmpty()) {
            // An EXPLICITLY empty list ("[]" or bare separators) must not silently widen
            // back to the full tool set — the model asked for nothing, which is a mistake
            // to surface, not a request for everything.
            throw new IllegalArgumentException("tools must name at least one tool of the "
                    + "subagent set " + ALLOWED_TOOLS + " (omit the parameter for the full set)");
        }
        for (String name : requested) {
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("tool '" + name
                        + "' is not available to subagents; the delegation set may only narrow "
                        + ALLOWED_TOOLS);
            }
        }
        // Preserve the canonical order regardless of how the model listed them.
        List<String> ordered = new ArrayList<>(ALLOWED_TOOLS);
        ordered.retainAll(new LinkedHashSet<>(requested));
        return List.copyOf(ordered);
    }

    /** Callbacks attached in the canonical ALLOWED_TOOLS order, resolved from the tool beans. */
    private List<ToolCallback> toolCallbacksFor(List<String> names) {
        Map<String, ToolCallback> byName = new LinkedHashMap<>();
        for (Object bean : List.of(fileTools, execTool, applyPatchTool)) {
            for (ToolCallback callback : ToolCallbacks.from(bean)) {
                byName.putIfAbsent(callback.getToolDefinition().name(), callback);
            }
        }
        List<ToolCallback> attached = new ArrayList<>();
        for (String name : names) { // resolveToolSet already canonicalized the order
            ToolCallback callback = byName.get(name);
            if (callback != null) attached.add(callback);
        }
        return attached;
    }

    // ── worktree reporting ───────────────────────────────────────────────────────────────

    /**
     * A changed worktree is KEPT (merging is a deliberate decision) and reported with its
     * branch, changed files, and diff stat; a clean one is removed on the spot.
     */
    private Map<String, Object> isolationReport(WorktreeIsolation.Worktree worktree) {
        Map<String, Object> isolation = new LinkedHashMap<>();
        try {
            List<String> changed = WorktreeIsolation.status(worktree.root());
            isolation.put("worktree", worktree.root().toString());
            isolation.put("branch", worktree.branch());
            isolation.put("changedFiles", changed);
            if (!changed.isEmpty()) {
                isolation.put("diffStat", WorktreeIsolation.diffStat(worktree.root()));
                isolation.put("mergeHint", "git -C " + worktree.repoRoot()
                        + " merge " + worktree.branch());
                isolation.put("kept", true);
            } else {
                isolation.put("kept", false);
            }
        } catch (Exception gitFailure) {
            isolation.put("warning", "worktree change inspection failed: "
                    + gitFailure.getMessage());
            isolation.put("worktree", worktree.root().toString());
            isolation.put("branch", worktree.branch());
            isolation.put("kept", true);
        }
        return isolation;
    }

    /**
     * Removes a CLEAN worktree on the spot — but never while the subagent thread may still
     * be writing into it (a cancelled run that outlived the grace window): deleting under
     * a live writer turns its next save into a confusing failure, so a possibly-live
     * runner's worktree is always kept for the user to merge or delete.
     */
    private void cleanupWorktree(WorktreeIsolation.Worktree worktree, boolean subagentMayStillRun) {
        try {
            if (subagentMayStillRun) return;
            if (!WorktreeIsolation.hasChanges(worktree.root())) {
                WorktreeIsolation.remove(worktree);
            }
        } catch (Exception cleanupFailure) {
            log.debug("worktree cleanup skipped: {}", cleanupFailure.toString());
        }
    }


    private static String error(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error", message == null ? "delegate_task failed" : message);
        return toJson(result);
    }

    private static String toJson(Map<String, Object> result) {
        try {
            return JSON.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"error\":\"tool result serialization failed\"}";
        }
    }
}
