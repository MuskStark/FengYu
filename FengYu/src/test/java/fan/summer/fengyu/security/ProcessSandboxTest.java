package fan.summer.fengyu.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProcessSandboxTest {
    @TempDir Path workdir;

    @Test
    void compatibilityBackendLeavesCommandUnchangedAndReportsDowngrade() {
        ProcessSandbox sandbox = new ProcessSandbox(ProcessSandbox.Backend.NONE);

        ProcessSandbox.Launch launch =
                sandbox.command(List.of("/bin/sh", "-lc", "pwd"), workdir, false);

        assertEquals(List.of("/bin/sh", "-lc", "pwd"), launch.command());
        assertFalse(launch.sandboxed());
        assertEquals("none", launch.backend().id());
    }

    @Test
    void bubblewrapLimitsWritesAndDisablesNetworkByDefault() {
        ProcessSandbox sandbox = new ProcessSandbox(ProcessSandbox.Backend.BUBBLEWRAP);

        ProcessSandbox.Launch launch =
                sandbox.command(List.of("/bin/sh", "-lc", "pwd"), workdir, false);

        assertTrue(launch.sandboxed());
        assertTrue(launch.command().contains("--ro-bind"));
        assertTrue(launch.command().contains("--unshare-net"));
        assertTrue(launch.command().contains(workdir.toAbsolutePath().normalize().toString()));
    }

    @Test
    void explicitlyApprovedNetworkDoesNotUnshareNetworkNamespace() {
        ProcessSandbox sandbox = new ProcessSandbox(ProcessSandbox.Backend.BUBBLEWRAP);

        ProcessSandbox.Launch launch =
                sandbox.command(List.of("/bin/sh", "-lc", "pwd"), workdir, true);

        assertFalse(launch.command().contains("--unshare-net"));
    }

    @Test
    void pluginWorkerFailsClosedWithoutNativeSandbox() {
        ProcessSandbox sandbox = new ProcessSandbox(ProcessSandbox.Backend.NONE);
        assertThrows(IllegalStateException.class, () -> sandbox.plugin(
                List.of("worker"), workdir, List.of(workdir), false, false));
    }

    @Test
    void macSandboxCanonicalizesWritableRootsBeforeBuildingProfile() throws Exception {
        ProcessSandbox sandbox = new ProcessSandbox(ProcessSandbox.Backend.SANDBOX_EXEC);
        Path writable = Files.createDirectories(workdir.resolve("worker-tmp"));

        ProcessSandbox.Launch launch = sandbox.plugin(
            List.of("worker"), workdir, List.of(writable), false, false);

        assertTrue(launch.command().get(2).contains(writable.toRealPath().toString()));
    }

    @Test
    void launchCarriesOnStartedCallback() {
        // NONE/BUBBLEWRAP/SANDBOX_EXEC Launches carry no onStarted hook; only WINDOWS_JOB does.
        ProcessSandbox noneSandbox = new ProcessSandbox(ProcessSandbox.Backend.NONE);
        ProcessSandbox.Launch l = noneSandbox.unrestricted(java.util.List.of("echo", "hi"));
        assertNull(l.onStarted(), "NONE backend has no onStarted hook");
    }

    @Test
    void windowsJobBackendIsNotSelectedOnNonWindows() {
        // detect() is private; infer via the public backend() default constructor.
        // On a non-Windows host WINDOWS_JOB must never be selected.
        assertNotEquals(ProcessSandbox.Backend.WINDOWS_JOB,
                new ProcessSandbox().backend(),
                "non-Windows host must not select WINDOWS_JOB");
    }
}
