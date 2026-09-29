package fan.summer.fengyu.ai.tools;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Git-worktree isolation behind delegate_task's isolate=true: creation on a dedicated
 * fengyu/task-* branch, change detection vs HEAD, removal with prune, and the clean
 * rejection of non-repository roots. Skips itself when no git binary exists.
 */
class WorktreeIsolationTest {

    @TempDir
    Path repo;
    @TempDir
    Path plainDir;

    private static boolean gitAvailable;

    @BeforeAll
    static void probeGit() {
        try {
            Process p = new ProcessBuilder("git", "--version").start();
            gitAvailable = p.waitFor() == 0;
        } catch (Exception e) {
            gitAvailable = false;
        }
    }

    @BeforeEach
    void initRepo() throws Exception {
        assumeTrue(gitAvailable, "git binary not available");
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "test@fengyu.local");
        git(repo, "config", "user.name", "FengYu Test");
        Files.writeString(repo.resolve("tracked.txt"), "original\n");
        git(repo, "add", "tracked.txt");
        git(repo, "commit", "-q", "-m", "initial");
    }

    @Test
    void createsAWorktreeOnItsOwnBranchAtHead() throws Exception {
        WorktreeIsolation.Worktree worktree = WorktreeIsolation.create(repo);
        assertTrue(worktree.branch().startsWith("fengyu/task-"));
        assertEquals(repo.toAbsolutePath().normalize(),
                worktree.repoRoot().toAbsolutePath().normalize());
        assertEquals("original\n", Files.readString(worktree.root().resolve("tracked.txt")));
        // A worktree carries a .git FILE (gitfile) at its root, so repoRoot resolves to the
        // worktree itself — the main repository's root is NOT the subagent's workspace.
        assertEquals(worktree.root().toAbsolutePath().normalize(),
                WorktreeIsolation.repoRoot(worktree.root()).toAbsolutePath().normalize());
        WorktreeIsolation.remove(worktree);
    }

    @Test
    void tracksChangesVersusHead() throws Exception {
        WorktreeIsolation.Worktree worktree = WorktreeIsolation.create(repo);
        try {
            assertFalse(WorktreeIsolation.hasChanges(worktree.root()));

            Files.writeString(worktree.root().resolve("tracked.txt"), "modified\n");
            Files.writeString(worktree.root().resolve("new.txt"), "added\n");

            assertTrue(WorktreeIsolation.hasChanges(worktree.root()));
            List<String> status = WorktreeIsolation.status(worktree.root());
            assertTrue(status.stream().anyMatch(line -> line.endsWith("tracked.txt")));
            assertTrue(status.stream().anyMatch(line -> line.endsWith("new.txt")));
            assertTrue(WorktreeIsolation.diffStat(worktree.root()).contains("tracked.txt"));
        } finally {
            WorktreeIsolation.remove(worktree);
        }
    }

    @Test
    void removalCleansUpAndPrunes() throws Exception {
        WorktreeIsolation.Worktree worktree = WorktreeIsolation.create(repo);
        Path root = worktree.root();
        assertTrue(Files.isDirectory(root));
        WorktreeIsolation.remove(worktree);
        assertFalse(Files.exists(root));
        assertEquals(1, git(repo, "worktree", "list").strip().split("\n").length,
                "only the main worktree remains");
    }

    @Test
    void nonRepositoryRootsAreRejected() {
        assertNull(WorktreeIsolation.repoRoot(plainDir));
        assertThrows(IllegalArgumentException.class, () -> WorktreeIsolation.create(plainDir));
    }

    private static String git(Path cwd, String... args) throws IOException, InterruptedException {
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
