package fan.summer.fengyu.security;

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
 * S3: the strict macOS Seatbelt profile for agent-exec'd commands. The profile-text
 * shape is pinned on every platform; the real sandbox-exec integration runs on macOS
 * hosts (the deny-default fence is the real thing — writes outside the root, protected
 * metadata writes, and network are hard-denied).
 */
class StrictSeatbeltProfileTest {

    @Test
    void profileTextDeniesByDefaultAndParametrizesRoots(@TempDir Path root) throws Exception {
        Path git = Files.createDirectories(root.resolve(".git"));
        Path resolvedRoot = root.toRealPath();

        String profile = StrictSeatbeltProfile.profile(
                List.of(root), List.of(git), false);

        assertTrue(profile.startsWith("(version 1)\n(deny default)"), "closed by default");
        assertTrue(profile.contains("(allow file-read*)"), "full-disk read tier");
        assertTrue(profile.contains("(allow file-write* (require-all (subpath (param \"WRITABLE_ROOT_0\"))"),
                "writes only inside the parametrized root");
        assertTrue(profile.contains("_EXCLUDED_0"), "protected metadata excluded via require-not");
        assertTrue(profile.contains(
                "(deny file-write-unlink (require-all (literal (param \"WRITABLE_ROOT_0\")) (vnode-type DIRECTORY)))"),
                "root anchor denies relocation of the carveout");
        assertTrue(profile.contains("(deny mach-lookup (xpc-service-name-prefix \"\"))"));
        assertTrue(profile.contains("(deny system-fcntl (fcntl-command 80 110))"));
        assertFalse(profile.contains("network-outbound"), "network stays denied: no network section");
        assertFalse(profile.contains(resolvedRoot.toString()),
                "paths never appear in the policy text — only as -D params");
    }

    @Test
    void readOnlyTierHasNoWriteAllowAndNoFcntlDeny() {
        String readOnly = StrictSeatbeltProfile.profile(List.of(), List.of(), false);
        assertFalse(readOnly.contains("(allow file-write*"));
        assertFalse(readOnly.contains("system-fcntl"));
    }

    @Test
    void openNetworkAppendsTheNetworkSection() {
        String open = StrictSeatbeltProfile.profile(List.of(), List.of(), true);
        assertTrue(open.contains("com.apple.SecurityServer"));
        assertTrue(open.contains("system-socket"));
    }

    @Test
    void argvUsesHardcodedExecutorAndDashDParams(@TempDir Path root) throws Exception {
        Path git = Files.createDirectories(root.resolve(".git"));
        List<String> argv = StrictSeatbeltProfile.sandboxExecArgv(
                List.of("/bin/sh", "-c", "echo hi"), List.of(root), List.of(git), false);

        assertEquals("/usr/bin/sandbox-exec", argv.get(0), "hardcoded executor (PATH-injection-proof)");
        assertEquals("-p", argv.get(1));
        assertEquals("--", argv.get(argv.size() - 4), "separator before the 3-element command");
        assertEquals("/bin/sh", argv.get(argv.size() - 3));
        assertTrue(argv.stream().anyMatch(a -> a.startsWith("-DWRITABLE_ROOT_0=")));
        assertTrue(argv.stream().anyMatch(a -> a.startsWith("-DWRITABLE_ROOT_0_EXCLUDED_0=")));
    }

    // ── real sandbox-exec integration (macOS only) ─────────────────────────────────────

    private static int run(List<String> argv, Path cwd) throws Exception {
        Process p = new ProcessBuilder(argv).redirectErrorStream(true).directory(cwd.toFile()).start();
        p.getOutputStream().close();
        p.waitFor();
        return p.exitValue();
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void fencedWriteInsideRootSucceedsAndOutsideFails(@TempDir Path root) throws Exception {
        assumeTrue(Files.isExecutable(Path.of(StrictSeatbeltProfile.SANDBOX_EXEC)), "sandbox-exec present");
        Path outside = Files.createDirectories(root.resolve("outside"));
        Path workspace = Files.createDirectories(root.resolve("ws"));

        List<String> inside = StrictSeatbeltProfile.sandboxExecArgv(
                List.of("/bin/sh", "-c", "echo hi > proof.txt"),
                List.of(workspace), List.of(), false);
        assertEquals(0, run(inside, workspace), "write INSIDE the writable root succeeds");
        assertTrue(Files.exists(workspace.resolve("proof.txt")));

        List<String> escape = StrictSeatbeltProfile.sandboxExecArgv(
                List.of("/bin/sh", "-c", "echo no > " + outside.resolve("escape.txt")),
                List.of(workspace), List.of(), false);
        assertTrue(run(escape, workspace) != 0, "write OUTSIDE the root is denied");
        assertFalse(Files.exists(outside.resolve("escape.txt")));
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void protectedGitStaysReadOnly(@TempDir Path root) throws Exception {
        assumeTrue(Files.isExecutable(Path.of(StrictSeatbeltProfile.SANDBOX_EXEC)), "sandbox-exec present");
        Path git = Files.createDirectories(root.resolve(".git"));

        List<String> writeGit = StrictSeatbeltProfile.sandboxExecArgv(
                List.of("/bin/sh", "-c", "echo x > " + git.resolve("config.new")),
                List.of(root), List.of(git), false);
        assertTrue(run(writeGit, root) != 0, "the excluded .git stays read-only");
        assertFalse(Files.exists(git.resolve("config.new")));
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void deniedNetworkBlocksOutbound(@TempDir Path root) throws Exception {
        assumeTrue(Files.isExecutable(Path.of(StrictSeatbeltProfile.SANDBOX_EXEC)), "sandbox-exec present");

        List<String> curl = StrictSeatbeltProfile.sandboxExecArgv(
                List.of("/usr/bin/curl", "--connect-timeout", "3", "-s", "http://example.com"),
                List.of(root), List.of(), false);
        assertTrue(run(curl, root) != 0, "outbound HTTP is denied under deny-default");
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void readOnlyCommandsStillRunInsideTheFence(@TempDir Path root) throws Exception {
        assumeTrue(Files.isExecutable(Path.of(StrictSeatbeltProfile.SANDBOX_EXEC)), "sandbox-exec present");

        List<String> inspect = StrictSeatbeltProfile.sandboxExecArgv(
                List.of("/bin/sh", "-c", "ls " + root + " && cat /etc/resolv.conf > /dev/null && echo ok"),
                List.of(root), List.of(), false);
        assertEquals(0, run(inspect, root), "reads (listing, /etc) still work in the full-disk-read tier");
    }
}
