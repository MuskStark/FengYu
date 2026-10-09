package fan.summer.fengyu.ai.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The workspace chip's git branch label: plain repos, linked worktrees ({@code .git} pointer
 * file), detached HEADs, and everything that is not a git checkout at all.
 */
class WorkspaceBranchLabelTest {

    @TempDir
    Path root;

    @Test
    void plainRepoHeadYieldsBranchName() throws IOException {
        Path repo = gitDirWithHead("ref: refs/heads/release/4.1.0\n");
        assertEquals("release/4.1.0", WorkspaceService.branchLabel(repo));
    }

    @Test
    void worktreeGitdirPointerResolves() throws IOException {
        // A linked worktree stores a ".git" FILE ("gitdir: <abs path>") pointing at the shared
        // metadata dir whose HEAD names the checked-out branch.
        Path worktreeMeta = Files.createDirectories(root.resolve("main/.git/worktrees/feature"));
        Files.writeString(worktreeMeta.resolve("HEAD"), "ref: refs/heads/feat/x\n");
        Path worktree = Files.createDirectories(root.resolve("checkout"));
        Files.writeString(worktree.resolve(".git"), "gitdir: " + worktreeMeta + "\n");
        assertEquals("feat/x", WorkspaceService.branchLabel(worktree));
    }

    @Test
    void detachedHeadYieldsShortSha() throws IOException {
        Path repo = gitDirWithHead("0123456789abcdef0123456789abcdef01234567\n");
        assertEquals("0123456", WorkspaceService.branchLabel(repo));
    }

    @Test
    void nonGitDirectoryYieldsNull() throws IOException {
        Path plain = Files.createDirectories(root.resolve("plain"));
        Files.writeString(plain.resolve("notes.txt"), "not a repo");
        assertNull(WorkspaceService.branchLabel(plain));
    }

    @Test
    void garbageGitPointerYieldsNull() throws IOException {
        Path repo = Files.createDirectories(root.resolve("weird"));
        Files.writeString(repo.resolve(".git"), "definitely not a gitdir pointer\n");
        assertNull(WorkspaceService.branchLabel(repo));
    }

    @Test
    void missingHeadFileYieldsNull() throws IOException {
        Path repo = Files.createDirectories(root.resolve("headless/.git"));
        assertNull(WorkspaceService.branchLabel(repo.getParent()));
    }

    private Path gitDirWithHead(String head) throws IOException {
        Path repo = Files.createDirectories(root.resolve("repo/.git"));
        Files.writeString(repo.resolve("HEAD"), head);
        return repo.getParent();
    }
}
