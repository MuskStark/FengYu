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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * delegate_task — the write-capable subagent tool: dispatch and result reporting, the
 * tool-set boundary (may only narrow the workspace family), the concurrency slot cap,
 * timeout with cancel propagation, failure mapping, and git-worktree isolation end to
 * end (subagent writes land in the worktree, never in the main workspace; the binding is
 * restored; a clean worktree is removed, a changed one is kept and reported).
 */
class DelegateTaskToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Long CONVERSATION = 45L;
    private static boolean gitAvailable;

    @TempDir
    Path root;
    @TempDir
    Path gitRepo;

    private WorkspaceReadState readState;
    private WorkspaceContext.Binding binding;

    @BeforeAll
    static void probeGit() {
        try {
            gitAvailable = new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            gitAvailable = false;
        }
    }

    @BeforeEach
    void bind() {
        readState = new WorkspaceReadState();
        binding = new WorkspaceContext.Binding(root, CONVERSATION);
        WorkspaceContext.set(binding);
    }

    @AfterEach
    void unbind() {
        WorkspaceContext.clear();
    }

    private DelegateTaskTool tool(SubagentRunner runner) {
        return tool(runner, 3);
    }

    private DelegateTaskTool tool(SubagentRunner runner, int slots) {
        return new DelegateTaskTool(new WorkspaceFileTools(readState), new WorkspaceExecTool(),
                new ApplyPatchTool(readState), runner, slots, 1);
    }

    /** Behavior of the fake sub-loop; may block or throw like the real one. */
    @FunctionalInterface
    private interface Behavior {
        DelegateTaskTool.SubagentRunner.Result apply(
                DelegateTaskTool.SubagentRunner.Spec spec) throws Exception;
    }

    /** Fake sub-loop: records the spec, runs the injected behavior, counts cancels. */
    private static final class FakeRunner implements SubagentRunner {
        final AtomicReference<Spec> seen = new AtomicReference<>();
        volatile boolean cancelled;
        private final Behavior behavior;

        FakeRunner(Behavior behavior) {
            this.behavior = behavior;
        }

        @Override public Result run(Spec spec) throws Exception {
            seen.set(spec);
            return behavior.apply(spec);
        }

        @Override public void cancel() {
            cancelled = true;
        }
    }

    private static List<String> toolNames(DelegateTaskTool.SubagentRunner.Spec spec) {
        return spec.tools().stream().map(t -> t.getToolDefinition().name()).toList();
    }

    // ── dispatch & result reporting ──────────────────────────────────────────────────────

    @Test
    void dispatchesTheTaskAndReturnsTheReport() throws Exception {
        FakeRunner runner = new FakeRunner(spec -> {
            assertEquals("fix the lint errors", spec.userPrompt());
            assertTrue(spec.systemPrompt().contains("task subagent"));
            return new DelegateTaskTool.SubagentRunner.Result("fixed 3 files", 42);
        });
        JsonNode result = JSON.readTree(
                tool(runner).delegateTask("fix the lint errors", null, null, null));

        assertTrue(result.path("success").asBoolean(), result.toString());
        assertEquals("fixed 3 files", result.path("report").asText());
        assertEquals(42, result.path("completionTokens").asInt());
        assertTrue(result.path("seconds").asDouble() >= 0);
        // Independent conversation: the spec carries its own workspace view, and the
        // default tool set is exactly the workspace family.
        assertEquals(binding, runner.seen.get().workspace());
        assertEquals(DelegateTaskTool.ALLOWED_TOOLS, toolNames(runner.seen.get()));
    }

    @Test
    void runnerFailureBecomesAnErrorEnvelope() throws Exception {
        FakeRunner runner = new FakeRunner(spec -> {
            throw new IllegalStateException("provider 500");
        });
        JsonNode result = JSON.readTree(
                tool(runner).delegateTask("anything", null, null, null));
        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("provider 500"));
    }

    // ── permission boundary: the tool set may only narrow ────────────────────────────────

    @Test
    void requestedToolsNarrowTheSetInCanonicalOrder() throws Exception {
        FakeRunner runner = new FakeRunner(
                spec -> new DelegateTaskTool.SubagentRunner.Result("ok", 1));
        JsonNode result = JSON.readTree(tool(runner).delegateTask(
                "task", "[\"edit_file\",\"read_file\"]", null, null));
        assertTrue(result.path("success").asBoolean(), result.toString());
        assertEquals(List.of("read_file", "edit_file"), toolNames(runner.seen.get()));

        // Comma-separated form parses identically.
        FakeRunner comma = new FakeRunner(
                spec -> new DelegateTaskTool.SubagentRunner.Result("ok", 1));
        tool(comma).delegateTask("task", "edit_file,  read_file", null, null);
        assertEquals(List.of("read_file", "edit_file"), toolNames(comma.seen.get()));
    }

    @Test
    void nonWorkspaceToolNamesAreRejectedOutright() throws Exception {
        FakeRunner runner = new FakeRunner(
                spec -> new DelegateTaskTool.SubagentRunner.Result("ok", 1));
        for (String widening : List.of(
                "[\"browser_navigate\"]", "[\"web_search\"]", "[\"explore\"]", "[\"nope\"]")) {
            JsonNode result = JSON.readTree(
                    tool(runner).delegateTask("task", widening, null, null));
            assertFalse(result.path("success").asBoolean(), widening);
            assertTrue(result.path("error").asText().contains("not available to subagents"),
                    widening);
        }
    }

    /** Regression: an EXPLICITLY empty tools list must not silently widen back to the full
     *  subagent set — "asked for nothing" is a mistake to surface, not a request for all. */
    @Test
    void explicitlyEmptyToolListIsRejectedInsteadOfWideningToTheFullSet() throws Exception {
        FakeRunner runner = new FakeRunner(
                spec -> new DelegateTaskTool.SubagentRunner.Result("ok", 1));
        JsonNode result = JSON.readTree(tool(runner).delegateTask("task", "[]", null, null));
        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("at least one"));
    }

    // ── concurrency cap and timeout ──────────────────────────────────────────────────────

    @Test
    void concurrencyCapRejectsOverflowWhileASlotIsHeld() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        FakeRunner runner = new FakeRunner(spec -> {
            started.countDown();
            release.await();
            return new DelegateTaskTool.SubagentRunner.Result("slow work done", 1);
        });
        DelegateTaskTool tool = tool(runner, 1);

        AtomicReference<JsonNode> first = new AtomicReference<>();
        Thread holder = Thread.ofVirtual().start(() -> {
            try {
                first.set(JSON.readTree(tool.delegateTask("slow task", null, null, null)));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS), "first delegation must start");

        JsonNode busy = JSON.readTree(tool.delegateTask("second task", null, null, null));
        assertFalse(busy.path("success").asBoolean());
        assertTrue(busy.path("error").asText().contains("subagent slots are busy"));

        release.countDown();
        holder.join(10_000);
        assertTrue(first.get().path("success").asBoolean(), "the holder finishes normally");
    }

    @Test
    void timeoutCancelsTheRunnerAndReportsIt() throws Exception {
        CountDownLatch stuck = new CountDownLatch(1);
        FakeRunner runner = new FakeRunner(spec -> {
            stuck.await(); // only the cancel() below releases it
            return new DelegateTaskTool.SubagentRunner.Result("never", 0);
        });
        long start = System.nanoTime();
        JsonNode result = JSON.readTree(
                tool(runner).delegateTask("task", null, 1, null));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("timed out"));
        assertTrue(runner.cancelled, "cancel must propagate into the sub-loop");
        assertTrue(elapsedMillis < 15_000, "timeout must fire near the 1s budget");
        stuck.countDown(); // let the lingered worker exit
        assertEquals(binding, WorkspaceContext.current(), "binding restored after timeout");
    }

    /** Regression: the timeout envelope actually CARRIES the worktree report the old error
     *  text promised, and a worktree with changes (or a still-live writer) is kept. */
    @Test
    void timedOutIsolatedDelegationReportsItsKeptWorktree() throws Exception {
        assumeTrue(gitAvailable, "git binary not available");
        git(gitRepo, "init", "-q");
        git(gitRepo, "config", "user.email", "test@fengyu.local");
        git(gitRepo, "config", "user.name", "FengYu Test");
        Files.writeString(gitRepo.resolve("base.txt"), "base\n");
        git(gitRepo, "add", "base.txt");
        git(gitRepo, "commit", "-q", "-m", "initial");
        WorkspaceContext.set(new WorkspaceContext.Binding(gitRepo, CONVERSATION));

        CountDownLatch stuck = new CountDownLatch(1);
        FakeRunner runner = new FakeRunner(spec -> {
            // Write through the real workspace tool under the subagent's worktree binding,
            // then block past the delegation timeout.
            new WorkspaceFileTools(new WorkspaceReadState())
                    .writeFile("late.txt", "written before the timeout\n", null);
            try {
                stuck.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return new DelegateTaskTool.SubagentRunner.Result("never", 0);
        });
        JsonNode result = JSON.readTree(
                tool(runner).delegateTask("make the change", null, 1, true));

        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("timed out"));
        JsonNode isolation = result.path("isolation");
        assertTrue(!isolation.isMissingNode(), "the timeout envelope carries the worktree report");
        assertTrue(isolation.path("kept").asBoolean(), isolation.toString());
        assertTrue(isolation.path("changedFiles").toString().contains("late.txt"));
        assertTrue(isolation.path("branch").asText().startsWith("fengyu/task-"));
        Path worktree = Path.of(isolation.path("worktree").asText());
        assertTrue(Files.exists(worktree), "a worktree with changes is never deleted");

        stuck.countDown(); // release the cancelled worker
    }

    // ── git worktree isolation ───────────────────────────────────────────────────────────

    @Test
    void isolationRunsTheSubagentInTheWorktreeNotTheMainWorkspace() throws Exception {
        assumeTrue(gitAvailable, "git binary not available");
        git(gitRepo, "init", "-q");
        git(gitRepo, "config", "user.email", "test@fengyu.local");
        git(gitRepo, "config", "user.name", "FengYu Test");
        Files.writeString(gitRepo.resolve("base.txt"), "base\n");
        git(gitRepo, "add", "base.txt");
        git(gitRepo, "commit", "-q", "-m", "initial");
        WorkspaceContext.set(new WorkspaceContext.Binding(gitRepo, CONVERSATION));

        FakeRunner runner = new FakeRunner(spec -> {
            // Write through the real workspace tool under the SUBAGENT's binding: proof the
            // whole nested loop is jailed to the worktree.
            new WorkspaceFileTools(new WorkspaceReadState())
                    .writeFile("subagent-change.txt", "from the subagent\n", null);
            return new DelegateTaskTool.SubagentRunner.Result(
                    "wrote subagent-change.txt in the isolated worktree", 7);
        });
        JsonNode result = JSON.readTree(
                tool(runner).delegateTask("make the change", null, null, true));

        assertTrue(result.path("success").asBoolean(), result.toString());
        JsonNode isolation = result.path("isolation");
        assertTrue(isolation.path("kept").asBoolean(), isolation.toString());
        assertTrue(isolation.path("branch").asText().startsWith("fengyu/task-"));
        assertTrue(isolation.path("changedFiles").toString().contains("subagent-change.txt"));
        assertTrue(isolation.path("mergeHint").asText().contains("merge"));
        Path worktree = Path.of(isolation.path("worktree").asText());
        assertEquals("from the subagent\n",
                Files.readString(worktree.resolve("subagent-change.txt")));
        assertFalse(Files.exists(gitRepo.resolve("subagent-change.txt")),
                "the MAIN workspace must stay untouched");
        // The main workspace's own dirty state (uncommitted base change) survives apart.
        Files.writeString(gitRepo.resolve("user-note.txt"), "user's own WIP\n");
        assertFalse(Files.exists(worktree.resolve("user-note.txt")),
                "the worktree must not absorb the user's uncommitted files");
        assertEquals("wrote subagent-change.txt in the isolated worktree",
                result.path("report").asText());
    }

    @Test
    void cleanWorktreeRunIsRemovedOnTheSpot() throws Exception {
        assumeTrue(gitAvailable, "git binary not available");
        git(gitRepo, "init", "-q");
        git(gitRepo, "config", "user.email", "test@fengyu.local");
        git(gitRepo, "config", "user.name", "FengYu Test");
        Files.writeString(gitRepo.resolve("base.txt"), "base\n");
        git(gitRepo, "add", "base.txt");
        git(gitRepo, "commit", "-q", "-m", "initial");
        WorkspaceContext.set(new WorkspaceContext.Binding(gitRepo, CONVERSATION));

        FakeRunner runner = new FakeRunner(
                spec -> new DelegateTaskTool.SubagentRunner.Result("nothing to do", 3));
        JsonNode result = JSON.readTree(
                tool(runner).delegateTask("look around", null, null, true));

        assertTrue(result.path("success").asBoolean(), result.toString());
        JsonNode isolation = result.path("isolation");
        assertFalse(isolation.path("kept").asBoolean());
        assertFalse(Files.exists(Path.of(isolation.path("worktree").asText())),
                "a clean worktree is removed immediately");
    }

    @Test
    void isolationWithoutAGitRepositoryFailsCleanly() throws Exception {
        FakeRunner runner = new FakeRunner(
                spec -> new DelegateTaskTool.SubagentRunner.Result("ok", 1));
        JsonNode result = JSON.readTree(
                tool(runner).delegateTask("task", null, null, true));
        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("git repository"));
    }

    private static void git(Path cwd, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(cwd.toFile()).redirectErrorStream(true).start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assumeTrue(process.waitFor() == 0, "git failed: " + output);
    }
}
