package fan.summer.fengyu.ai.sandbox;

import fan.summer.fengyu.security.ProcessSandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * S2: the Linux bwrap fence for the agent exec path. The argv shape is pinned on every
 * platform (it mirrors codex's {@code linux-sandbox/src/bwrap.rs} mount order); the
 * real-bwrap integration runs only on Linux with bwrap present (CI) — on other hosts it
 * records an honest skip, it never pretends.
 */
class AgentSandboxBwrapTest {

    @Test
    void bwrapArgvMirrorsTheCodexMountOrder(@TempDir Path tmp) throws Exception {
        Path workdir = java.nio.file.Files.createDirectories(tmp);
        Path git = java.nio.file.Files.createDirectories(workdir.resolve(".git"));
        // The mount-consistent (symlink-resolved) form — chdir must match the binds.
        Path resolved = workdir.toRealPath();
        Path resolvedGit = git.toRealPath();
        List<String> argv = ProcessSandbox.agentBwrapArgv(
                List.of("/bin/sh", "-c", "make test"),
                workdir,
                List.of(workdir, Path.of("/nonexistent-root")),
                List.of(git),
                false);

        assertEquals(List.of(
                "bwrap",
                "--die-with-parent",
                "--new-session",
                "--ro-bind", "/", "/",
                "--dev", "/dev",
                "--bind-try", "/dev/shm", "/dev/shm",
                // writable roots AFTER the full-root ro-bind, in order, existing only
                "--bind", resolved.toString(), resolved.toString(),
                // metadata re-protection AFTER the bind so it wins
                "--ro-bind", resolvedGit.toString(), resolvedGit.toString(),
                "--unshare-user",
                "--unshare-pid",
                "--unshare-ipc",
                "--unshare-net",
                "--proc", "/proc",
                "--chdir", resolved.toString(),
                "--cap-drop", "ALL",
                "--",
                "/bin/sh", "-c", "make test"), argv,
                "mount order per codex create_bwrap_flags: full-root ro → dev/shm → binds → "
                        + "ro re-protections → namespaces → proc → chdir → cap-drop → command");
    }

    @Test
    void networkAllowedOmitsNetns(@TempDir Path workdir) {
        List<String> argv = ProcessSandbox.agentBwrapArgv(
                List.of("curl", "example.com"), workdir, List.of(workdir), List.of(), true);
        assertFalse(argv.contains("--unshare-net"));
        assertTrue(argv.contains("--unshare-user"));
        assertTrue(argv.contains("--cap-drop"));
    }

    @Test
    void protectedMetadataSubpathsCoverExistingGitAndFengyuOnly(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve(".git"));
        Files.createDirectories(root.resolve(".fengyu"));
        Files.createDirectories(root.resolve("src"));

        assertEquals(List.of(root.resolve(".git"), root.resolve(".fengyu")),
                AgentSandboxManager.protectedMetadataSubpaths(List.of(root)),
                "existing .git/.fengyu directly under the writable root are re-protected; "
                        + "src is not metadata");
        assertEquals(List.of(),
                AgentSandboxManager.protectedMetadataSubpaths(List.of(root.resolve("src"))),
                "no .git under src → nothing re-protected (bwrap skips missing targets anyway)");
    }

    // ── real-bwrap integration (Linux + bwrap only; CI gate) ───────────────────────────

    @Test
    @EnabledOnOs(OS.LINUX)
    void sandboxedWorkspaceWriteSucceedsAndOutsideWritesFail(@TempDir Path root) throws Exception {
        assumeTrue(ProcessSandbox.isNativeSandboxAvailable(),
                "no bwrap on this host — skipped and recorded, not failed");

        Path outside = Files.createDirectory(root.resolve("outside"));
        Path workspace = Files.createDirectory(root.resolve("ws"));
        ProcessSandbox sandbox = new ProcessSandbox();
        assumeTrue(sandbox.backend() == ProcessSandbox.Backend.BUBBLEWRAP, "bwrap backend expected");

        ProcessSandbox.Launch writeInside = sandbox.agentCommand(
                List.of("/bin/sh", "-c", "echo hi > proof.txt"), workspace,
                List.of(workspace), List.of(), false);
        Process p = new ProcessBuilder(writeInside.command()).redirectErrorStream(true).start();
        p.waitFor();
        assertEquals(0, p.exitValue(), "writing INSIDE the writable root succeeds");
        assertTrue(Files.exists(workspace.resolve("proof.txt")));

        ProcessSandbox.Launch writeOutside = sandbox.agentCommand(
                List.of("/bin/sh", "-c", "echo no > " + outside.resolve("escape.txt")),
                workspace, List.of(workspace), List.of(), false);
        Process q = new ProcessBuilder(writeOutside.command()).redirectErrorStream(true).start();
        q.waitFor();
        assertFalse(Files.exists(outside.resolve("escape.txt")),
                "writing OUTSIDE the writable root is rejected (exit " + q.exitValue() + ")");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void deniedNetworkIsActuallyUnshared(@TempDir Path root) throws Exception {
        assumeTrue(ProcessSandbox.isNativeSandboxAvailable(), "no bwrap — skipped and recorded");
        ProcessSandbox sandbox = new ProcessSandbox();
        assumeTrue(sandbox.backend() == ProcessSandbox.Backend.BUBBLEWRAP, "bwrap backend expected");

        // Inside a fresh netns with no interfaces, even DNS resolution must fail fast.
        ProcessSandbox.Launch noNet = sandbox.agentCommand(
                List.of("/bin/sh", "-c", "getent hosts example.com"),
                root, List.of(root), List.of(), false);
        Process p = new ProcessBuilder(noNet.command()).redirectErrorStream(true).start();
        p.waitFor();
        assertFalse(p.exitValue() == 0, "name resolution must fail inside --unshare-net");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void gitMetadataUnderWritableRootStaysReadOnly(@TempDir Path root) throws Exception {
        assumeTrue(ProcessSandbox.isNativeSandboxAvailable(), "no bwrap — skipped and recorded");
        ProcessSandbox sandbox = new ProcessSandbox();
        assumeTrue(sandbox.backend() == ProcessSandbox.Backend.BUBBLEWRAP, "bwrap backend expected");
        Path git = Files.createDirectories(root.resolve(".git"));

        ProcessSandbox.Launch writeGit = sandbox.agentCommand(
                List.of("/bin/sh", "-c", "echo x > " + git.resolve("config.new")),
                root, List.of(root), AgentSandboxManager.protectedMetadataSubpaths(List.of(root)), false);
        Process p = new ProcessBuilder(writeGit.command()).redirectErrorStream(true).start();
        p.waitFor();
        assertFalse(Files.exists(git.resolve("config.new")),
                "the re-protected .git stays read-only inside the sandbox");
    }
}
