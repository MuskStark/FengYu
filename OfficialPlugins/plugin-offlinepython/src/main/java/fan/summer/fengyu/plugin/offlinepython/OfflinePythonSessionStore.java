package fan.summer.fengyu.plugin.offlinepython;

import fan.summer.fengyu.plugin.offlinepython.domain.BuildConfig;
import fan.summer.fengyu.plugin.offlinepython.infra.JsonStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session {@link BuildConfig} store, mirroring {@code ExcelSessionStore}. A session is an
 * opaque client-chosen string (UI) or the fixed {@link #AI_SESSION} (AI tools). Each session tracks
 * the project directory it is bound to and lazily loads {@code <projectDir>/config.json} on first
 * bind, falling back to {@link BuildConfig#defaults()} when absent.
 */
public final class OfflinePythonSessionStore {

    /** Shared session key used by the stateless AI tools so a model can drive the flow. */
    public static final String AI_SESSION = "ai";

    private final ConcurrentHashMap<String, BuildConfig> configs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Path> projectDirs = new ConcurrentHashMap<>();

    /** Get or lazily create the session's BuildConfig (defaults until {@link #bind} loads a file). */
    public BuildConfig get(String session) {
        return configs.computeIfAbsent(session, k -> BuildConfig.defaults());
    }

    /** Bind a session to a project directory, loading config.json if present. */
    public BuildConfig bind(String session, Path projectDir) {
        projectDirs.put(session, projectDir);
        return configs.computeIfAbsent(session, k -> loadOrDefault(projectDir));
    }

    /** Replace the session's config (used by config.save). */
    public BuildConfig put(String session, BuildConfig cfg) {
        configs.put(session, cfg);
        return cfg;
    }

    public Optional<Path> projectDir(String session) {
        return Optional.ofNullable(projectDirs.get(session));
    }

    /** The most recently active session (simple heuristic: first entry). Used by AI tools. */
    public Optional<String> activeSession() {
        return configs.keySet().stream().findFirst();
    }

    public void remove(String session) {
        configs.remove(session);
        projectDirs.remove(session);
    }

    private static BuildConfig loadOrDefault(Path projectDir) {
        Path cfg = projectDir.resolve("config.json");
        if (Files.exists(cfg)) {
            try { return JsonStore.load(cfg, BuildConfig.class); }
            catch (Exception ignored) { /* fall through to defaults */ }
        }
        return BuildConfig.defaults();
    }
}
