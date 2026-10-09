package fan.summer.fengyu.ai.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The branch-switch pipeline end to end: synthetic refs parsing for listings, and — against a
 * real {@code git} — the switch/create flows, the pre-flight blockers, and the stderr
 * classifier that turns git's refusals into stable issue codes.
 */
class WorkspaceGitSwitchTest {

    @TempDir
    Path root;

    // ── listings (synthetic, no git binary) ─────────────────────────────────────────

    @Test
    void listsLooseAndPackedBranchesWithCurrentFlag() throws IOException {
        Path repo = Files.createDirectories(root.resolve("repo/.git/refs/heads/feature"));
        Files.createDirectories(root.resolve("repo/.git/refs/heads"));
        Files.writeString(root.resolve("repo/.git/refs/heads/main"), "1111111111111111111111111111111111111111\n");
        Files.writeString(repo.resolve("sub"), "2222222222222222222222222222222222222222\n");
        Files.writeString(root.resolve("repo/.git/HEAD"), "ref: refs/heads/main\n");
        Files.writeString(root.resolve("repo/.git/packed-refs"), """
                # pack-refs with: peeled fully-peeled sorted
                3333333333333333333333333333333333333333 refs/heads/release/4.0
                ^4444444444444444444444444444444444444444
                """);
        List<WorkspaceService.BranchInfo> branches = WorkspaceService.listBranches(root.resolve("repo"));
        assertNotNull(branches);
        assertEquals(List.of("feature/sub", "main", "release/4.0"),
                branches.stream().map(WorkspaceService.BranchInfo::name).toList());
        assertEquals("main", branches.stream().filter(WorkspaceService.BranchInfo::current).findFirst().orElseThrow().name());
    }

    @Test
    void linkedWorktreeListsSharedRefsAndReadsOwnHead() throws IOException {
        Path mainGit = Files.createDirectories(root.resolve("main/.git"));
        Files.writeString(mainGit.resolve("HEAD"), "ref: refs/heads/main\n");
        Files.createDirectories(mainGit.resolve("refs/heads"));
        Files.writeString(mainGit.resolve("refs/heads/main"), "1111111111111111111111111111111111111111\n");
        Files.writeString(mainGit.resolve("refs/heads/feature"), "2222222222222222222222222222222222222222\n");

        Path worktreeMeta = Files.createDirectories(mainGit.resolve("worktrees/wt"));
        Files.writeString(worktreeMeta.resolve("HEAD"), "ref: refs/heads/feature\n");
        Path worktree = Files.createDirectories(root.resolve("checkout"));
        Files.writeString(worktree.resolve(".git"), "gitdir: " + worktreeMeta + "\n");

        List<WorkspaceService.BranchInfo> branches = WorkspaceService.listBranches(worktree);
        assertNotNull(branches);
        assertEquals("feature", WorkspaceService.branchLabel(worktree));
        assertTrue(branches.stream().anyMatch(b -> b.name().equals("main") && !b.current()));
        assertTrue(branches.stream().anyMatch(b -> b.name().equals("feature") && b.current()));
    }

    @Test
    void nonGitRootYieldsNullAndUnbornRepoYieldsEmptyList() throws IOException {
        assertNull(WorkspaceService.listBranches(Files.createDirectories(root.resolve("plain"))));
        Path fresh = Files.createDirectories(root.resolve("fresh/.git"));
        Files.writeString(fresh.resolve("HEAD"), "ref: refs/heads/main\n");
        List<WorkspaceService.BranchInfo> branches = WorkspaceService.listBranches(fresh.getParent());
        assertNotNull(branches);
        assertEquals(List.of(), branches);
    }

    // ── switch flows (real git) ─────────────────────────────────────────────────────

    @Test
    void switchesCreatesAndClassifiesRefusals() throws Exception {
        Path repo = Files.createDirectories(root.resolve("repo"));
        git(repo, "init", "-b", "main");
        Files.writeString(repo.resolve("a.txt"), "1\n");
        git(repo, "add", "a.txt");
        git(repo, "commit", "-m", "base");
        git(repo, "branch", "feature");
        git(repo, "checkout", "-q", "feature");
        Files.writeString(repo.resolve("a.txt"), "2\n");
        git(repo, "commit", "-am", "feature changes a");
        git(repo, "checkout", "-q", "main");

        // Clean switch to the other branch.
        WorkspaceService.BranchMutation switched = WorkspaceService.switchBranch(repo, "feature", false);
        assertTrue(switched.ok());
        assertTrue(switched.didChange());
        assertEquals("feature", switched.branchLabel());
        assertEquals("2", Files.readString(repo.resolve("a.txt")).strip());

        // Switching to where we already are is a no-op success.
        WorkspaceService.BranchMutation noOp = WorkspaceService.switchBranch(repo, "feature", false);
        assertTrue(noOp.ok());
        assertEquals(false, noOp.didChange());

        // Creating an existing branch refuses with branch-already-exists.
        WorkspaceService.BranchMutation duplicate = WorkspaceService.switchBranch(repo, "main", true);
        assertEquals(false, duplicate.ok());
        assertEquals("branch-already-exists", duplicate.issues().get(0).code());

        // A dirty tracked file the target branch touches blocks the switch and names the file.
        Files.writeString(repo.resolve("a.txt"), "3\n");
        WorkspaceService.BranchMutation blocked = WorkspaceService.switchBranch(repo, "main", false);
        assertEquals(false, blocked.ok());
        WorkspaceService.BranchIssue issue = blocked.issues().get(0);
        assertEquals("tracked-changes-would-be-overwritten", issue.code());
        assertEquals(List.of("a.txt"), issue.paths());
        git(repo, "checkout", "-q", "--", "a.txt");

        // An unknown branch classifies as target-branch-not-found; a crafted name never runs.
        WorkspaceService.BranchMutation missing = WorkspaceService.switchBranch(repo, "nope", false);
        assertEquals("target-branch-not-found", missing.issues().get(0).code());
        WorkspaceService.BranchMutation invalid = WorkspaceService.switchBranch(repo, "--exec=x", false);
        assertEquals("invalid-branch-name", invalid.issues().get(0).code());

        // A branch held by another worktree is named as such.
        Path worktree = root.resolve("wt");
        git(repo, "worktree", "add", worktree.toString(), "main");
        WorkspaceService.BranchMutation held = WorkspaceService.switchBranch(repo, "main", false);
        assertEquals(false, held.ok());
        assertEquals("branch-in-other-worktree", held.issues().get(0).code());

        // In-flight rebase state preempts the process entirely (current branch is "feature",
        // so the no-op early-out must not shadow this — target a different branch).
        Files.createDirectories(repo.resolve(".git").resolve("rebase-merge"));
        WorkspaceService.BranchMutation ongoing = WorkspaceService.switchBranch(repo, "main", false);
        assertEquals(false, ongoing.ok());
        assertEquals("operation-in-progress", ongoing.issues().get(0).code());
    }

    /** Runs git with a throwaway identity; fails the test with the output if git errors. */
    private static void git(Path cwd, String... args) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(
                "git", "-c", "user.email=t@example.com", "-c", "user.name=T").directory(cwd.toFile());
        builder.command().addAll(List.of(args));
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process process = builder.start();
        String output;
        try (var stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(0, process.waitFor(), () -> "git " + String.join(" ", args) + " failed: " + output);
    }
}
