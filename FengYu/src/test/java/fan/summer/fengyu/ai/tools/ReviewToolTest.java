package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.ai.tools.DelegateTaskTool.SubagentRunner;
import fan.summer.fengyu.ai.workspace.WorkspaceContext;
import fan.summer.fengyu.ai.workspace.WorkspaceReadState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The review subagent: the three review targets resolve to whitelisted read-only git
 * commands (validated against the exec policy before anything runs), hostile refs are
 * rejected, the reviewer thread receives ONLY read tools and a prompt carrying the full
 * rubric + the diff, an empty diff short-circuits without spawning a reviewer, and the
 * result envelope carries the structured report.
 */
class ReviewToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Long CONVERSATION = 46L;
    private static boolean gitAvailable;

    @TempDir
    Path repo;

    private WorkspaceReadState readState;
    private ReviewTool tool;
    private FakeRunner runner;

    @BeforeAll
    static void probeGit() {
        try {
            gitAvailable = new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            gitAvailable = false;
        }
    }

    @BeforeEach
    void bind() throws Exception {
        assumeTrue(gitAvailable, "git binary not available");
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "test@fengyu.local");
        git(repo, "config", "user.name", "FengYu Test");
        Files.writeString(repo.resolve("base.txt"), "base\n");
        git(repo, "add", "base.txt");
        git(repo, "commit", "-q", "-m", "initial");
        // Idempotent: the initial branch may already be named main (init.defaultBranch).
        git(repo, "branch", "-M", "main");

        readState = new WorkspaceReadState();
        runner = new FakeRunner();
        tool = new ReviewTool(new WorkspaceFileTools(readState), new WorkspaceExecTool(),
                runner, 2);
        WorkspaceContext.set(new WorkspaceContext.Binding(repo, CONVERSATION));
    }

    @AfterEach
    void unbind() {
        WorkspaceContext.clear();
    }

    private static final class FakeRunner implements SubagentRunner {
        final AtomicReference<Spec> seen = new AtomicReference<>();
        final AtomicInteger invocations = new AtomicInteger();

        @Override public Result run(Spec spec) {
            seen.set(spec);
            invocations.incrementAndGet();
            return new Result("""
                    VERDICT: approve-with-changes — the change is sound but misses an edge case.
                    FINDINGS:
                    - P1 src/App.java:12 — the loop can read past the array end when the input
                      is empty; guard the index before use.
                    SUMMARY: one focused finding; could not run the test suite here.""", 55);
        }

        @Override public void cancel() {}
    }

    // ── target resolution & readonly boundary ────────────────────────────────────────────

    @Test
    void threeTargetsResolveToWhitelistedReadonlyGit() {
        ReviewTool.ReviewTarget uncommitted = ReviewTool.resolveTarget(null, null);
        assertEquals("uncommitted", uncommitted.kind());
        assertEquals(List.of("git status --porcelain", "git diff HEAD"), uncommitted.commands());

        ReviewTool.ReviewTarget base = ReviewTool.resolveTarget("release/4.1.0", null);
        assertEquals("base", base.kind());
        assertEquals(List.of("git diff release/4.1.0...HEAD"), base.commands());

        ReviewTool.ReviewTarget commit = ReviewTool.resolveTarget(null, "abc123");
        assertEquals("commit", commit.kind());
        assertEquals(List.of("git show --stat abc123", "git show abc123"), commit.commands());

        // The readonly boundary is structural: EVERY generated command must independently
        // pass the exec whitelist that auto-approves inspections.
        for (ReviewTool.ReviewTarget target : List.of(uncommitted, base, commit)) {
            for (String command : target.commands()) {
                assertTrue(WorkspaceExecTool.isReadonlyCommandLine(command),
                        target.kind() + " command must be whitelisted: " + command);
            }
        }
    }

    @Test
    void hostileRefsAreRejectedBeforeAnyGitRun() throws Exception {
        for (String bad : List.of("main; rm -rf .", "$(whoami)", "HEAD~1", "..", "-b", "a b",
                "main..other", "x`pwd`")) {
            JsonNode byBranch = JSON.readTree(tool.review(null, bad, null, null));
            assertFalse(byBranch.path("success").asBoolean(), bad);
            assertTrue(byBranch.path("error").asText().contains("baseBranch"), bad);
        }
        for (String bad : List.of("HEAD;rm", "zz--", "abc;git push")) {
            JsonNode byCommit = JSON.readTree(tool.review(null, null, bad, null));
            assertFalse(byCommit.path("success").asBoolean(), bad);
            assertTrue(byCommit.path("error").asText().contains("hex sha"), bad);
        }
        assertEquals(0, runner.invocations.get(), "no reviewer may spawn for bad refs");
    }

    // ── rubric injection, tool boundary, report shape ────────────────────────────────────

    @Test
    void reviewsUncommittedDiffWithRubricAndReadOnlyTools() throws Exception {
        Files.writeString(repo.resolve("base.txt"), "modified by the agent\n");
        Files.writeString(repo.resolve("new.txt"), "added\n");

        JsonNode result = JSON.readTree(tool.review("concurrency safety", null, null, 10));

        assertTrue(result.path("success").asBoolean(), result.toString());
        assertEquals("uncommitted", result.path("target").asText());
        assertEquals(55, result.path("completionTokens").asInt());
        assertTrue(result.path("seconds").asDouble() >= 0);
        assertTrue(result.path("report").asText().startsWith("VERDICT:"));

        SubagentRunner.Spec spec = runner.seen.get();
        List<String> names = spec.tools().stream()
                .map(t -> t.getToolDefinition().name()).toList();
        assertEquals(3, names.size(), "the reviewer is read-only by construction");
        assertTrue(names.containsAll(List.of("read_file", "grep", "glob")),
                names + " must be exactly the read-only trio");
        // Rubric: severities, comment discipline, AGENTS.md attribution, verdict shape.
        String system = spec.systemPrompt();
        for (String required : List.of("P0", "P1", "P2", "P3", "one paragraph",
                "three", "AGENTS.md", "VERDICT", "INTRODUCES")) {
            assertTrue(system.contains(required), "rubric must mention: " + required);
        }
        String prompt = spec.userPrompt();
        assertTrue(prompt.contains("Reviewer focus: concurrency safety"));
        assertTrue(prompt.contains("git status --porcelain"));
        assertTrue(prompt.contains("git diff HEAD"));
        assertTrue(prompt.contains("modified by the agent"), "the diff body is embedded");
        assertTrue(prompt.contains("new.txt"), "untracked files from status are included");
    }

    @Test
    void baseBranchAndCommitTargetsReachTheReviewer() throws Exception {
        // Park the base branch at the initial commit, then commit on a feature branch so
        // "git diff main...HEAD" has real content.
        git(repo, "checkout", "-q", "-b", "feature");
        Files.writeString(repo.resolve("base.txt"), "changed on a branch\n");
        git(repo, "add", "base.txt");
        git(repo, "commit", "-q", "-m", "second");
        String sha = git(repo, "rev-parse", "--short", "HEAD").strip();

        JsonNode byCommit = JSON.readTree(tool.review(null, null, sha, 10));
        assertTrue(byCommit.path("success").asBoolean(), byCommit.toString());
        assertEquals("commit:" + sha, byCommit.path("target").asText());
        assertTrue(runner.seen.get().userPrompt().contains("git show " + sha));

        JsonNode byBase = JSON.readTree(tool.review(null, "main", null, 10));
        assertTrue(byBase.path("success").asBoolean(), byBase.toString());
        assertEquals("base:main", byBase.path("target").asText());
        assertTrue(runner.seen.get().userPrompt().contains("git diff main...HEAD"));
    }

    @Test
    void emptyDiffShortCircuitsWithoutSpawningAReviewer() throws Exception {
        JsonNode result = JSON.readTree(tool.review(null, null, null, 10));
        assertTrue(result.path("success").asBoolean(), result.toString());
        assertTrue(result.path("report").asText().contains("No changes to review"));
        assertEquals(0, runner.invocations.get(), "a clean tree must not pay for a reviewer");
    }

    private static String git(Path cwd, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(cwd.toFile()).redirectErrorStream(true).start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assumeTrue(process.waitFor() == 0, "git failed: " + output);
        return output;
    }
}
