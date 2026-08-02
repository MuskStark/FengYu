package fan.summer.fengyu.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
