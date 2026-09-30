package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.security.ProcessSandbox;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only disclosure of the active process-isolation posture.
 *
 * <p>Reports two distinct capability dimensions so callers never mistake process-tree lifecycle
 * isolation (Windows Job Object) for a filesystem/network security boundary:
 * <ul>
 *   <li>{@code sandboxed} — whether a real OS security boundary (bwrap / sandbox-exec) is active.</li>
 *   <li>{@code lifecycleIsolation} — how process-tree termination is enforced
 *       ({@code job-object} on Windows, {@code none} elsewhere).</li>
 * </ul>
 * On Windows {@code sandboxed} is {@code false} and {@code compatibilityMode} is {@code true}: the
 * Job Object reclaims the process tree on host exit but does NOT prevent a plugin from reading host
 * files or reaching the network. UI and permission gates use {@code sandboxed} (not merely "a
 * backend is active") to decide whether to expose the unsandboxed toggle and whether to require
 * explicit approval.
 */
@RestController
public class SecurityController {

    private final ProcessSandbox sandbox;

    public SecurityController(ProcessSandbox sandbox) {
        this.sandbox = sandbox;
    }

    @GetMapping("/api/security/process-isolation")
    public Map<String, Object> processIsolation() {
        ProcessSandbox.Backend backend = sandbox.backend();
        boolean fullIsolation = backend.providesSecurityIsolation();
        boolean reducedIsolation = backend.reducedIsolation();
        // compatibilityMode = no isolation at all (full or reduced); macOS (reduced) and Linux
        // (full) are NOT in compatibility mode, while Windows (lifecycle-only) and NONE are.
        boolean compatibilityMode = !fullIsolation && !reducedIsolation;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("backend", backend.id());
        body.put("sandboxed", fullIsolation);
        body.put("reduced", reducedIsolation);
        body.put("compatibilityMode", compatibilityMode);
        body.put("lifecycleIsolation", lifecycleIsolationId(backend));
        body.put("policy", "compatibility-first-explicit-approval");
        return body;
    }

    /** Human-readable lifecycle-isolation id for the active backend. */
    private static String lifecycleIsolationId(ProcessSandbox.Backend backend) {
        // BUBBLEWRAP / SANDBOX_EXEC both kill the process tree via their wrappers (--die-with-parent
        // / sandbox-exec session); WINDOWS_JOB provides lifecycle-only isolation; NONE provides none.
        return switch (backend) {
            case WINDOWS_JOB -> "job-object";
            case NONE -> "none";
            default -> backend.id();
        };
    }
}
