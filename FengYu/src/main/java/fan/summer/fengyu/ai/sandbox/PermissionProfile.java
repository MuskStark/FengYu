package fan.summer.fengyu.ai.sandbox;

import java.nio.file.Path;
import java.util.List;

/**
 * The agent-facing sandbox permission tiers — the Java mirror of codex's
 * {@code SandboxPolicy} (protocol.rs:1072; {@code ExternalSandbox} omitted, it targets
 * external orchestrators we do not have):
 *
 * <ul>
 *   <li>{@link ReadOnly} — inspections run fenced, no writes anywhere, no network.</li>
 *   <li>{@link WorkspaceWrite} — project work runs fenced with the workspace (plus
 *       configured extra roots) writable; network follows the setting.</li>
 *   <li>{@link FullAccess} — the honest name for today's behavior: no fence at all
 *       (codex {@code danger-full-access}).</li>
 * </ul>
 */
public sealed interface PermissionProfile permits
        PermissionProfile.ReadOnly,
        PermissionProfile.WorkspaceWrite,
        PermissionProfile.FullAccess {

    /** The kebab-case tier name used in settings, audit JSON, and rollout fields. */
    String tier();

    /** Read-only tier: everything is readable, nothing writable, network denied. */
    record ReadOnly(Path root) implements PermissionProfile {
        @Override public String tier() { return "read-only"; }
    }

    /** Workspace-write tier: {@code writableRoots} writable, the rest readable, network per setting. */
    record WorkspaceWrite(Path root, List<Path> writableRoots, boolean networkAccess)
            implements PermissionProfile {
        public WorkspaceWrite {
            writableRoots = writableRoots == null ? List.of() : List.copyOf(writableRoots);
        }

        @Override public String tier() { return "workspace-write"; }
    }

    /** Full access: no fence (the pre-sandbox behavior; the {@code off} setting maps here). */
    record FullAccess() implements PermissionProfile {
        @Override public String tier() { return "danger-full-access"; }
    }
}
