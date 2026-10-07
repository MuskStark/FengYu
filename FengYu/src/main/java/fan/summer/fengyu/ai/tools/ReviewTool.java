package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * {@code review} — the code-review subagent: dispatches the current diff to an independent
 * reviewer thread (fresh conversation, read-only tools) that grades it against a terminal
 * coding-agent review rubric and returns findings with P0–P3 severities and an overall
 * correctness verdict. Only the verdict report crosses back to the main conversation.
 *
 * <p>Three review targets, all acquired through git commands that sit on the
 * {@link WorkspaceExecTool} READ-ONLY whitelist (so they auto-run without approval in every
 * permission mode): the uncommitted work ({@code git status --porcelain} +
 * {@code git diff HEAD}), everything since a base branch ({@code git diff <base>...HEAD},
 * i.e. from the merge base), or one specific commit ({@code git show}). Refs are validated
 * against strict character sets and every generated command is re-checked with
 * {@link WorkspaceExecTool#isReadonlyCommandLine(String)} before it runs — the reviewer
 * delegation is read-only BY CONSTRUCTION, not by prompt discipline alone: the sub-thread
 * only ever receives {@code read_file}/{@code grep}/{@code glob}.</p>
 */
@Component
public class ReviewTool implements FengYuTool, ToolEffectProvider {

    private static final Logger log = LoggerFactory.getLogger(ReviewTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final long DEFAULT_TIMEOUT_SECONDS = 240;
    static final long MAX_TIMEOUT_SECONDS = 600;
    static final int MAX_DIFF_CHARS = 120_000;
    static final int REVIEW_SLOTS = 2;

    /** A hex commit id (4–40 chars) — anything else is rejected before it reaches git. */
    private static final Pattern COMMIT_SHA = Pattern.compile("^[0-9a-fA-F]{4,40}$");

    /** A git branch/ref name without the shells and ranges that could smuggle flags. */
    private static final Pattern BRANCH_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]*$");

    static final String REVIEW_RUBRIC = """
            You are a senior code reviewer. You are given a diff (plus status output) from a
            coding workspace and must review it for problems the change INTRODUCES. Inspect
            the surrounding code with your read_file/grep/glob tools before flagging anything
            — a line that looks wrong in isolation is often correct in context.

            What counts as a finding:
            - discrete, actionable issues introduced by this change (bugs, security problems,
              data loss, broken contracts, regression risks, missing error handling);
            - violations of the project's own conventions from its AGENTS.md, when one exists
              — attribute each such finding to the rule it breaks;
            - NOT style preferences, NOT pre-existing issues, NOT hypotheticals you cannot
              point at in the diff or the surrounding code.

            Severity levels:
            - P0 must fix before merge: correctness or security blocker, data loss, crash;
            - P1 should fix: likely bug, unhandled edge case, real regression risk;
            - P2 worth fixing: maintainability, missing test for new behavior, minor perf;
            - P3 optional nit.

            Comment discipline: every finding is AT MOST one paragraph, quotes AT MOST three
            lines of code, and cites the location as path/to/file.ext:line.

            Answer in exactly this shape:
            VERDICT: approve | approve-with-changes | reject — one-sentence overall
              correctness rationale.
            FINDINGS:
            - P1 path/to/file.ext:42 — one-paragraph issue, ≤3 quoted lines, the fix.
            (one line per finding, most severe first; "none" when the diff is clean)
            SUMMARY: 2–3 sentences on the change's overall quality and anything you could
              not verify (e.g. tests you could not run).""";

    /** One review target: which diff to review and the whitelisted git commands for it. */
    record ReviewTarget(String kind, String ref, List<String> commands, String description) {}

    private final WorkspaceFileTools fileTools;
    private final WorkspaceExecTool execTool;
    private final DelegateTaskTool.SubagentRunner runner;
    private final Semaphore slots;

    @Autowired
    public ReviewTool(WorkspaceFileTools fileTools, WorkspaceExecTool execTool,
            ChatToolApprovalGate approvalGate) {
        this(fileTools, execTool, new CloudSubagentRunner(approvalGate), REVIEW_SLOTS);
    }

    /** Test constructor: inject the runner seam and a tighter slot count. */
    ReviewTool(WorkspaceFileTools fileTools, WorkspaceExecTool execTool,
            DelegateTaskTool.SubagentRunner runner, int slots) {
        this.fileTools = fileTools;
        this.execTool = execTool;
        this.runner = runner;
        this.slots = new Semaphore(slots);
    }

    @Override
    public ToolEffect effectFor(String toolName) {
        // Acquiring the diff runs only whitelisted read-only git commands, and the reviewer
        // sub-thread is read-only — the whole review is an inspection, free in every mode.
        return "review".equals(toolName) ? ToolEffect.READ : null;
    }

    // ── review ───────────────────────────────────────────────────────────────────────────

    @Tool(name = "review",
          description = "Send the current code changes to an independent reviewer subagent "
                  + "and get back P0-P3 graded findings plus an approve/reject verdict "
                  + "(terminal coding-agent /review). Targets: leave baseBranch and commit "
                  + "empty to review the UNCOMMITTED changes; pass baseBranch (e.g. 'main') "
                  + "to review everything since that branch; pass a commit sha to review that "
                  + "single commit. The reviewer sees the diff plus read-only workspace tools. "
                  + "Run it over your own work before declaring it done.")
    public String review(
            @ToolParam(required = false,
                       description = "Optional focus for the reviewer (what to pay attention "
                              + "to, e.g. 'concurrency safety of the new cache').")
            String focus,
            @ToolParam(required = false,
                       description = "Base branch to review against (default: review the "
                              + "uncommitted changes instead).")
            String baseBranch,
            @ToolParam(required = false,
                       description = "Commit sha to review as a single change (overrides "
                              + "baseBranch).")
            String commitSha,
            @ToolParam(required = false,
                       description = "Wall-clock budget in seconds (default 240, max 600).")
            Integer timeoutSeconds) {
        WorkspaceContext.Binding binding = WorkspaceContext.current();
        if (binding == null) return error("This conversation has no workspace attached");

        ReviewTarget target;
        try {
            target = resolveTarget(baseBranch, commitSha);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
        long timeout = timeoutSeconds == null ? DEFAULT_TIMEOUT_SECONDS
                : Math.max(1, Math.min(timeoutSeconds, (int) MAX_TIMEOUT_SECONDS));

        try {
            if (!slots.tryAcquire(10, TimeUnit.SECONDS)) {
                return error("All review slots are busy; retry shortly");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error("review interrupted while waiting for a slot");
        }

        long startNanos = System.nanoTime();
        try {
            // Boundary check FIRST: every command must sit on the read-only whitelist —
            // a non-whitelisted command would silently degrade to the approval path or,
            // worse, run unchecked; refuse instead.
            for (String command : target.commands()) {
                if (!WorkspaceExecTool.isReadonlyCommandLine(command)) {
                    return error("internal error: review command is not on the read-only "
                            + "whitelist: " + command);
                }
            }
            StringBuilder bundle = new StringBuilder();
            for (String command : target.commands()) {
                String output = runGit(command);
                if (output == null) return error("git failed while preparing the review "
                        + "target; is the workspace a git repository? (" + command + ")");
                if (!output.isBlank()) {
                    bundle.append("$ ").append(command).append('\n').append(output.strip())
                            .append("\n\n");
                }
            }
            String diffBundle = bundle.toString().strip();
            if (diffBundle.isEmpty()) {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("success", true);
                empty.put("target", target.kind());
                empty.put("report", "No changes to review for target: " + target.description());
                return toJson(empty);
            }

            List<ToolCallback> readonlyTools = new ArrayList<>();
            for (ToolCallback callback : ToolCallbacks.from(fileTools)) {
                String name = callback.getToolDefinition().name();
                if ("read_file".equals(name) || "grep".equals(name) || "glob".equals(name)) {
                    readonlyTools.add(callback);
                }
            }

            String userPrompt = "Review target: " + target.description()
                    + (focus == null || focus.isBlank() ? "" : "\nReviewer focus: " + focus.strip())
                    + "\n\nWorkspace output follows (status and diff):\n\n"
                    + truncateMiddle(diffBundle);

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<DelegateTaskTool.SubagentRunner.Result> result =
                    new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread.ofVirtual().start(() -> {
                try {
                    result.set(runner.run(new DelegateTaskTool.SubagentRunner.Spec(
                            REVIEW_RUBRIC, userPrompt, readonlyTools, binding)));
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    done.countDown();
                }
            });
            if (!done.await(timeout, TimeUnit.SECONDS)) {
                runner.cancel();
                done.await(5, TimeUnit.SECONDS);
                return error("review timed out after " + timeout + "s; the reviewer was "
                        + "cancelled — try a narrower target (a single commit or branch)");
            }
            Throwable runFailure = failure.get();
            if (runFailure != null) {
                log.debug("review subagent failed: {}", runFailure.toString());
                return error("reviewer failed: " + (runFailure.getMessage() == null
                        ? runFailure.toString() : runFailure.getMessage()));
            }
            DelegateTaskTool.SubagentRunner.Result run = result.get();
            String report = run == null || run.report() == null ? "" : run.report().strip();

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("success", !report.isEmpty());
            envelope.put("target", target.kind() + (target.ref() == null ? "" : ":" + target.ref()));
            envelope.put("report", report.isEmpty()
                    ? "(the reviewer finished without a report)" : report);
            envelope.put("seconds", (System.nanoTime() - startNanos) / 1_000_000_000L);
            if (run != null) envelope.put("completionTokens", run.completionTokens());
            return toJson(envelope);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            runner.cancel();
            return error("review interrupted; the reviewer was cancelled");
        } catch (RuntimeException e) {
            return error(e.getMessage());
        } finally {
            slots.release();
        }
    }

    // ── target resolution (pure, testable) ───────────────────────────────────────────────

    /**
     * commitSha &gt; baseBranch &gt; uncommitted. Refs are validated against strict
     * character sets — they are interpolated into git commands, so anything that could
     * smuggle flags, ranges, or shell syntax is rejected here.
     */
    static ReviewTarget resolveTarget(String baseBranch, String commitSha) {
        if (commitSha != null && !commitSha.isBlank()) {
            String sha = commitSha.strip();
            if (!COMMIT_SHA.matcher(sha).matches()) {
                throw new IllegalArgumentException("commit must be a 4-40 character hex sha, "
                        + "not: " + sha);
            }
            return new ReviewTarget("commit", sha,
                    List.of("git show --stat " + sha, "git show " + sha),
                    "commit " + sha);
        }
        if (baseBranch != null && !baseBranch.isBlank()) {
            String branch = baseBranch.strip();
            if (!BRANCH_NAME.matcher(branch).matches() || branch.contains("..")) {
                throw new IllegalArgumentException("baseBranch must be a plain branch name "
                        + "(letters, digits, '.', '_', '/', '-'), not: " + branch);
            }
            return new ReviewTarget("base", branch,
                    List.of("git diff " + branch + "...HEAD"),
                    "changes since branch " + branch + " (from the merge base)");
        }
        return new ReviewTarget("uncommitted", null,
                List.of("git status --porcelain", "git diff HEAD"),
                "uncommitted changes (staged, unstaged; untracked listed by status)");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    /** Runs one whitelisted git command through the jailed exec tool; null on failure. */
    private String runGit(String command) {
        try {
            JsonNode result = JSON.readTree(execTool.workspaceExec(command, null, 30, null));
            if (!result.path("success").asBoolean()) return null;
            return result.path("output").asText("");
        } catch (Exception e) {
            return null;
        }
    }

    /** Head+tail truncation with a dropped-characters marker, mirroring exec output caps. */
    static String truncateMiddle(String bundle) {
        if (bundle.length() <= MAX_DIFF_CHARS) return bundle;
        int head = MAX_DIFF_CHARS / 2;
        int tail = MAX_DIFF_CHARS - head;
        return bundle.substring(0, head)
                + "\n…[" + (bundle.length() - MAX_DIFF_CHARS) + " characters of the diff "
                + "dropped — review with git commands yourself if the tail matters]\n"
                + bundle.substring(bundle.length() - tail);
    }

    private static String error(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error", message == null ? "review failed" : message);
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
