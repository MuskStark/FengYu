package fan.summer.fengyu.ai.sandbox;

import fan.summer.fengyu.security.ProcessSandbox;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1 pure functions: settings → profile resolution and the profile × backend planning
 * matrix. Honesty rules pinned: only a backend that enforces a full fence is reported
 * {@code sandboxed}; anything else reports {@code degraded} with a note — never a
 * silent downgrade (the {@code ProcessSandbox.Backend} tradition). On this tree macOS
 * reports degraded until the S3 strict-SBPL profile lands (the existing reduced
 * sandbox-exec profile is the JVM-plugin-worker constraint, not an exec fence).
 */
class AgentSandboxManagerTest {

    private static final Path ROOT = Path.of("/ws/project");

    // ── settings → profile ─────────────────────────────────────────────────────────────

    @Test
    void offModeResolvesToFullAccess() {
        assertEquals(PermissionProfile.FullAccess.class,
                AgentSandboxManager.profileFor("off", ROOT).getClass());
        assertEquals(PermissionProfile.FullAccess.class,
                AgentSandboxManager.profileFor(null, ROOT).getClass());
        assertEquals(PermissionProfile.FullAccess.class,
                AgentSandboxManager.profileFor("garbage", ROOT).getClass());
    }

    @Test
    void readOnlyAndWorkspaceWriteResolveWithRoot() {
        PermissionProfile.ReadOnly readOnly =
                (PermissionProfile.ReadOnly) AgentSandboxManager.profileFor("read-only", ROOT);
        assertEquals(ROOT, readOnly.root());

        PermissionProfile.WorkspaceWrite write =
                (PermissionProfile.WorkspaceWrite) AgentSandboxManager.profileFor("workspace-write", ROOT);
        assertEquals(ROOT, write.root());
        assertEquals(List.of(ROOT), write.writableRoots(),
                "without settings storage the workspace root is the only writable root");
        assertFalse(write.networkAccess(), "network defaults to denied");
    }

    // ── profile × backend planning ─────────────────────────────────────────────────────

    @Test
    void fullAccessNeverReportsASandbox() {
        for (ProcessSandbox.Backend backend : ProcessSandbox.Backend.values()) {
            AgentSandboxManager.SandboxLaunch launch =
                    AgentSandboxManager.plan(new PermissionProfile.FullAccess(), backend);
            assertEquals("none", launch.backend());
            assertEquals("danger-full-access", launch.profile());
            assertFalse(launch.sandboxed());
            assertFalse(launch.degraded(), "full access asks for no fence — nothing is degraded");
            assertTrue(launch.networkAllowed());
        }
    }

    @Test
    void bubblewrapFencesReadOnlyAndWorkspaceWrite() {
        AgentSandboxManager.SandboxLaunch readOnly =
                AgentSandboxManager.plan(new PermissionProfile.ReadOnly(ROOT), ProcessSandbox.Backend.BUBBLEWRAP);
        assertTrue(readOnly.sandboxed());
        assertFalse(readOnly.degraded());
        assertEquals("bubblewrap", readOnly.backend());
        assertEquals("read-only", readOnly.profile());
        assertFalse(readOnly.networkAllowed());

        AgentSandboxManager.SandboxLaunch write = AgentSandboxManager.plan(
                new PermissionProfile.WorkspaceWrite(ROOT, List.of(ROOT, Path.of("/tmp/out")), true),
                ProcessSandbox.Backend.BUBBLEWRAP);
        assertTrue(write.sandboxed());
        assertEquals(List.of("/ws/project", "/tmp/out"), write.writableRoots());
        assertTrue(write.networkAllowed());
    }

    @Test
    void sandboxExecNowFencesTheAgentPathWhileJobAndNoneDegrade() {
        // S3: the strict SBPL profile fences agent-exec'd commands (unlike the reduced
        // JVM-worker profile, which is what Backend.providesSecurityIsolation() alone
        // would report).
        AgentSandboxManager.SandboxLaunch mac =
                AgentSandboxManager.plan(new PermissionProfile.ReadOnly(ROOT), ProcessSandbox.Backend.SANDBOX_EXEC);
        assertTrue(mac.sandboxed());
        assertFalse(mac.degraded());
        assertEquals("sandbox-exec", mac.backend());

        for (ProcessSandbox.Backend backend : java.util.Arrays.asList(
                ProcessSandbox.Backend.WINDOWS_JOB, ProcessSandbox.Backend.NONE, null)) {
            AgentSandboxManager.SandboxLaunch launch =
                    AgentSandboxManager.plan(new PermissionProfile.WorkspaceWrite(ROOT, List.of(ROOT), false),
                            backend);
            assertFalse(launch.sandboxed(), String.valueOf(backend));
            assertTrue(launch.degraded(), String.valueOf(backend));
            assertEquals("none", launch.backend());
            assertTrue(launch.note() != null && !launch.note().isBlank(),
                    "degradation always carries an explanation");
        }
    }

    @Test
    void auditShapeCarriesBackendProfileNetworkAndDegradation() {
        Map<String, Object> degraded = AgentSandboxManager.plan(
                new PermissionProfile.ReadOnly(ROOT), ProcessSandbox.Backend.NONE).toAudit();
        assertEquals("none", degraded.get("backend"));
        assertEquals("read-only", degraded.get("profile"));
        assertEquals("denied", degraded.get("network"));
        assertEquals(Boolean.FALSE, degraded.get("sandboxed"));
        assertEquals(Boolean.TRUE, degraded.get("degraded"));
        assertTrue(degraded.containsKey("note"));

        Map<String, Object> fenced = AgentSandboxManager.plan(
                new PermissionProfile.WorkspaceWrite(ROOT, List.of(ROOT), false),
                ProcessSandbox.Backend.BUBBLEWRAP).toAudit();
        assertEquals(Boolean.TRUE, fenced.get("sandboxed"));
        assertFalse(fenced.containsKey("degraded"));
        assertEquals(List.of("/ws/project"), fenced.get("writableRoots"));
    }
}
