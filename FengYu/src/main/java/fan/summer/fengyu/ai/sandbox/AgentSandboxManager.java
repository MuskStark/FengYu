package fan.summer.fengyu.ai.sandbox;

import fan.summer.fengyu.ai.AiConfigService;
import fan.summer.fengyu.security.ProcessSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The single choke point the coding-agent exec path passes through (the counterpart of
 * codex's {@code SandboxManager::transform}): every {@code workspace_exec} /
 * interactive-session {@link ProcessBuilder} goes through {@link #transform}, which
 * resolves the {@link PermissionProfile} from settings, plans what the platform backend
 * will do, and (from S2/S3 on) rewrites the argv into the bwrap / strict-SBPL fence.
 *
 * <p><b>S1 scope:</b> profile resolution, planning, and honest degradation reporting —
 * {@link #transform} is an audit/env passthrough (no argv rewrite yet), so turning the
 * setting on changes only the {@code sandbox} field in the tool result JSON, never the
 * process launch itself. Degradation is ALWAYS reported as degradation (the
 * {@code ProcessSandbox.Backend} honesty tradition): a platform without a security
 * fence says so instead of pretending.</p>
 */
@Component
public class AgentSandboxManager {

    private static final Logger log = LoggerFactory.getLogger(AgentSandboxManager.class);

    private final ProcessSandbox processSandbox = new ProcessSandbox();

    @Autowired
    public AgentSandboxManager() {
    }

    /**
     * What the platform will do for one launch: the backend that would fence it (or
     * {@code "none"}), the resolved profile tier, and whether that falls short of the
     * requested isolation ({@code degraded} — e.g. Windows, or Linux without bwrap).
     */
    public record SandboxLaunch(String backend, String profile, List<String> writableRoots,
            boolean networkAllowed, boolean sandboxed, boolean degraded, String note) {

        /** The {@code sandbox} object appended to the exec tool's result JSON. */
        public Map<String, Object> toAudit() {
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("backend", backend);
            audit.put("profile", profile);
            if (!writableRoots.isEmpty()) audit.put("writableRoots", writableRoots);
            audit.put("network", networkAllowed ? "open" : "denied");
            audit.put("sandboxed", sandboxed);
            if (degraded) audit.put("degraded", true);
            if (note != null && !note.isBlank()) audit.put("note", note);
            return audit;
        }
    }

    // ── settings → profile ──────────────────────────────────────────────────────────────

    /** The sandbox mode setting: {@code off} (default), {@code read-only}, {@code workspace-write}. */
    public static String sandboxMode() {
        return AiConfigService.getAiSandboxMode();
    }

    /** Sandbox enabled = the mode is anything other than {@code off}. */
    public static boolean enabled() {
        return !"off".equals(sandboxMode());
    }

    /** Resolves the settings into a profile for the given workspace root. */
    public static PermissionProfile profileFor(String mode, Path workspaceRoot) {
        return switch (mode == null ? "off" : mode) {
            case "read-only" -> new PermissionProfile.ReadOnly(workspaceRoot);
            case "workspace-write" -> new PermissionProfile.WorkspaceWrite(
                    workspaceRoot,
                    writableRoots(workspaceRoot),
                    "open".equals(AiConfigService.getAiSandboxNetwork()));
            default -> new PermissionProfile.FullAccess();
        };
    }

    /** The workspace root plus the configured extra writable roots (comma-separated absolute paths). */
    private static List<Path> writableRoots(Path workspaceRoot) {
        List<Path> roots = new ArrayList<>();
        roots.add(workspaceRoot);
        String extra = AiConfigService.getAiSandboxExtraWritableRoots();
        if (extra != null && !extra.isBlank()) {
            for (String raw : extra.split(",")) {
                String trimmed = raw.trim();
                if (!trimmed.isEmpty()) roots.add(Path.of(trimmed));
            }
        }
        return List.copyOf(roots);
    }

    // ── planning (pure) ─────────────────────────────────────────────────────────────────

    /** Pure: what the given backend would do for the given profile. */
    public static SandboxLaunch plan(PermissionProfile profile, ProcessSandbox.Backend backend) {
        String backendId = backend == null ? ProcessSandbox.Backend.NONE.id() : backend.id();
        if (profile instanceof PermissionProfile.FullAccess) {
            return new SandboxLaunch("none", profile.tier(), List.of(), true, false, false,
                    "full access: no sandbox requested");
        }
        // The agent exec fence: bwrap full-root view on Linux, the strict deny-default
        // SBPL on macOS (StrictSeatbeltProfile — a shell command survives deny-default,
        // unlike the JVM plugin workers the reduced profile exists for).
        boolean fenced = backend != null
                && (backend.providesSecurityIsolation() || backend == ProcessSandbox.Backend.SANDBOX_EXEC);
        boolean network = profile instanceof PermissionProfile.WorkspaceWrite write && write.networkAccess();
        List<String> roots = profile instanceof PermissionProfile.WorkspaceWrite write
                ? write.writableRoots().stream().map(Path::toString).toList()
                : List.of();
        if (fenced) {
            return new SandboxLaunch(backendId, profile.tier(), roots, network, true, false, null);
        }
        String note = backend == null || backend == ProcessSandbox.Backend.NONE
                ? "no native sandbox on this platform — running UNFENCED per the honest-degradation policy"
                : "backend '" + backendId + "' provides lifecycle isolation only — running UNFENCED";
        return new SandboxLaunch("none", profile.tier(), roots, network, false, true, note);
    }

    /** Instance: resolves the platform backend and plans the given profile. */
    public SandboxLaunch plan(PermissionProfile profile) {
        return plan(profile, processSandbox.backend());
    }

    // ── the choke point ─────────────────────────────────────────────────────────────────

    /**
     * Every coding-exec {@link ProcessBuilder} passes through here before {@code start()}.
     * Resolves the plan, reports degradation honestly, and on a fencing backend rewrites
     * the argv into the fence (S2: the Linux bwrap full-root view; S3 adds the macOS
     * strict SBPL). Degraded platforms run UNFENCED with the degradation reported in the
     * result JSON — never silently.
     */
    public SandboxLaunch transform(ProcessBuilder builder, PermissionProfile profile) {
        SandboxLaunch launch = plan(profile);
        if (launch.sandboxed()) {
            List<Path> writable = profile instanceof PermissionProfile.WorkspaceWrite write
                    ? write.writableRoots() : List.of();
            boolean network = profile instanceof PermissionProfile.WorkspaceWrite write
                    && write.networkAccess();
            Path workdir = builder.directory() != null
                    ? builder.directory().toPath() : Path.of(".").toAbsolutePath();
            // Mount-consistent cwd: the sandbox params carry symlink-resolved paths
            // (/var → /private/var on macOS), so the child's working directory must be
            // resolved too or relative writes land outside the writable root and get
            // denied (the codex normalize_command_cwd lesson, seatbelt edition).
            Path resolvedWorkdir = resolveForSandbox(workdir);
            ProcessSandbox.Launch wrapped = processSandbox.agentCommand(
                    builder.command(), resolvedWorkdir, writable,
                    protectedMetadataSubpaths(writable), network);
            builder.command(wrapped.command());
            builder.directory(resolvedWorkdir.toFile());
            log.debug("sandbox plan: backend={} profile={}", launch.backend(), launch.profile());
        } else if (launch.degraded()) {
            log.warn("sandbox degraded for profile '{}': {}", profile.tier(), launch.note());
        }
        return launch;
    }

    /**
     * Existing {@code .git}/{@code .fengyu} directories directly under a writable root —
     * re-protected read-only after the bind (the codex protected-metadata model; S3 adds
     * the macOS equivalents).
     */
    /** Symlink-resolved form matching the sandbox's mount params; falls back to normalized. */
    static Path resolveForSandbox(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            return absolute.toRealPath();
        } catch (java.io.IOException unresolved) {
            return absolute;
        }
    }

    static List<Path> protectedMetadataSubpaths(List<Path> writableRoots) {
        List<Path> protectedSubs = new ArrayList<>();
        for (Path root : writableRoots) {
            for (String name : List.of(".git", ".fengyu")) {
                Path candidate = root.resolve(name);
                if (java.nio.file.Files.isDirectory(candidate)) protectedSubs.add(candidate);
            }
        }
        return List.copyOf(protectedSubs);
    }

    /** Convenience: settings → profile → transform, for the workspace exec path. */
    public SandboxLaunch transformForSettings(ProcessBuilder builder, Path workspaceRoot) {
        return transform(builder, profileFor(sandboxMode(), workspaceRoot));
    }

    /** Lowercase OS name helper shared by the S2/S3 backend probes. */
    static String osName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }
}
