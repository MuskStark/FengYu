package fan.summer.fengyu.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds an OS-sandboxed process command when a supported native isolator is available.
 *
 * <p>Compatibility-first policy: Linux uses bubblewrap, macOS uses sandbox-exec, Windows uses a
 * Job Object (process-tree lifecycle isolation via {@link WindowsJobSandbox}), and platforms
 * without a supported isolator return the original command with {@link Backend#NONE}. Callers must
 * surface and audit that downgrade. The chat permission gate decides whether an AI-authored
 * command needs an approval; the explicit full-access profile deliberately bypasses this sandbox.
 */
@Component
public class ProcessSandbox {
    private static final Logger log = LoggerFactory.getLogger(ProcessSandbox.class);

    public enum Backend {
        BUBBLEWRAP("bubblewrap"),
        SANDBOX_EXEC("sandbox-exec"),
        WINDOWS_JOB("windows-job"),
        NONE("none");

        private final String id;

        Backend(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /**
         * {@code true} when this backend enforces a FULL OS-level filesystem/network security
         * boundary. Only {@link #BUBBLEWRAP} (Linux) does: it builds a minimal read-only view that
         * excludes the user home, so a plugin genuinely cannot read host secrets. The
         * permission/approval gates key on this — treating a reduced-or-none backend as a full
         * security sandbox reports a false sense of security.
         *
         * <p>{@link #SANDBOX_EXEC} (macOS) is intentionally NOT full: a JVM cannot launch under a
         * strict deny-default on macOS, so the profile is deny-sensitive (allow-default + explicit
         * denies of known credential dirs) rather than a true minimal allowlist. A plugin can still
         * read non-allowlisted user files, so it is reported as {@link #reducedIsolation()}, not
         * full. {@link #WINDOWS_JOB} confines the process tree's lifecycle only.
         */
        public boolean providesSecurityIsolation() {
            return this == BUBBLEWRAP;
        }

        /**
         * {@code true} when this backend provides a partial/reduced security boundary (better than
         * bare, but not the full minimal-allowlist isolation the gates require for
         * "sandboxed" reporting). {@link #SANDBOX_EXEC} (macOS) — deny-sensitive, denies known
         * credential paths but cannot be deny-default. Windows Job Object and NONE are not reduced.
         */
        public boolean reducedIsolation() {
            return this == SANDBOX_EXEC;
        }

        /** {@code true} when this backend reliably terminates the worker process tree on host exit. */
        public boolean providesLifecycleIsolation() {
            return this != NONE;
        }
    }

    public record Launch(List<String> command, Backend backend,
                         java.util.function.BiConsumer<Process, long[]> onStarted) {
        public Launch {
            command = List.copyOf(command);
        }

        /** Backwards-compatible 2-arg constructor: no onStarted hook (NONE/bwrap/sandbox-exec). */
        public Launch(List<String> command, Backend backend) {
            this(command, backend, null);
        }

        public boolean sandboxed() {
            return backend != Backend.NONE;
        }

        /**
         * {@code true} when the backend enforces an OS filesystem/network security boundary. Distinct
         * from {@link #sandboxed()} (which only means "a backend is active"): {@code WINDOWS_JOB}
         * provides lifecycle isolation only. Callers that need the security boundary (permission
         * gates, the settings UI's compatibility-mode branch, the process-isolation endpoint)
         * should use this rather than {@link #sandboxed()}.
         */
        public boolean securityIsolated() {
            return backend.providesSecurityIsolation();
        }
    }

    private final Backend backend;

    public ProcessSandbox() {
        this(detect());
    }

    public ProcessSandbox(Backend backend) {
        this.backend = backend;
        if (backend == Backend.NONE) {
            log.warn("No supported native process sandbox found; execution will use explicit-approval compatibility mode");
        } else {
            log.info("Native process sandbox available: {}", backend.id());
        }
    }

    public Backend backend() {
        return backend;
    }

    /**
     * Terminate the job tree (Windows). No-op when {@code jobHandle == 0} or on a non-Windows host.
     * Delegates to {@link WindowsJobSandbox#terminate(long)}; exposed publicly because callers that
     * own a job handle (e.g. {@code PluginProcessManager.Worker}) live outside this package and
     * cannot reach the package-private {@code WindowsJobSandbox} directly.
     */
    public void terminateJob(long jobHandle) {
        WindowsJobSandbox.terminate(jobHandle);
    }

    /**
     * Close the job handle (triggers {@code KILL_ON_JOB_CLOSE} on any survivors). No-op when
     * {@code jobHandle == 0} or on a non-Windows host. See {@link #terminateJob(long)}.
     */
    public void closeJobHandle(long jobHandle) {
        WindowsJobSandbox.closeHandle(jobHandle);
    }

    /**
     * Returns whether this host can enforce a native filesystem/network security boundary. This is
     * the gate the permission/approval policy and the settings UI key on: when it returns
     * {@code false}, plugins and AI-authored commands run in compatibility mode (fail-closed,
     * explicit approval) rather than under a real OS sandbox.
     *
     * <p>Note this is narrower than "a backend is active": a {@link Backend#WINDOWS_JOB Windows Job
     * Object} is active and provides process-tree lifecycle isolation, but it does NOT isolate
     * filesystem or network access. Reporting Windows as having a security sandbox (the old
     * behavior) was a false sense of security. Until Windows AppContainer/restricted-token work
     * lands, this returns {@code false} on Windows so the host treats it as a no-security-sandbox
     * platform honestly.
     */
    public static boolean isNativeSandboxAvailable() {
        return detect().providesSecurityIsolation();
    }

    /**
     * Sandbox an AI-authored shell command. The command may read system files needed by the
     * runtime, but writes are limited to the selected working directory and network is isolated
     * unless the user explicitly approved it.
     */
    public Launch command(List<String> raw, Path workingDirectory, boolean allowNetwork) {
        return wrap(raw, workingDirectory, List.of(workingDirectory), List.of(), allowNetwork);
    }

    /** Explicit full-access profile: run without the native sandbox after the user selected it. */
    public Launch unrestricted(List<String> raw) {
        return new Launch(raw, Backend.NONE);
    }

    /**
     * Sandbox a plugin Worker according to its installed permissions: writes are limited to the
     * caller-authorized roots (the plugin-owned data dir plus explicit FileRef grants) —
     * {@code files.write} grants FileRef write access, not a broad filesystem write. Network is
     * isolated unless declared by the manifest.
     */
    public Launch plugin(List<String> raw, Path pluginRoot, List<Path> writableRoots,
                         boolean allowNetwork) {
        return plugin(raw, pluginRoot, writableRoots, List.of(), allowNetwork);
    }

    /** Sandbox a plugin with separate read-only and read-write authorized file roots. */
    public Launch plugin(List<String> raw, Path pluginRoot, List<Path> writableRoots,
                         List<Path> readableRoots, boolean allowNetwork) {
        if (backend == Backend.NONE) {
            throw new IllegalStateException("Plugin workers require a supported native process sandbox");
        }
        return wrap(raw, pluginRoot, writableRoots, readableRoots, allowNetwork);
    }

    private Launch wrap(List<String> raw, Path workdir, List<Path> writableRoots,
                        List<Path> readableRoots, boolean allowNetwork) {
        if (backend == Backend.NONE) return new Launch(raw, backend);
        if (backend == Backend.WINDOWS_JOB) {
            // Job Objects are assigned AFTER the process starts; the command itself is unchanged.
            // The caller passes a long[1] receiver; the hook writes the job handle into it so the
            // caller can later terminate/close the job (see WindowsJobSandbox).
            java.util.function.BiConsumer<Process, long[]> onStarted = (process, handleOut) -> {
                long job = WindowsJobSandbox.createAndConfigureJob();
                // Publish ownership immediately so the caller can reclaim the handle even if the
                // native assignment hook throws an Error after creating the Job Object.
                handleOut[0] = job;
                try {
                    WindowsJobSandbox.assign(job, process);
                } catch (RuntimeException | Error e) {
                    WindowsJobSandbox.closeHandle(job);  // reclaim the created-but-unassigned job
                    handleOut[0] = 0L;
                    throw e;
                }
            };
            return new Launch(raw, backend, onStarted);
        }
        if (backend == Backend.BUBBLEWRAP) {
            // Minimal read-only view: instead of bind-mounting the entire host root (the old
            // --ro-bind / /, which exposed the user's home, SSH config, ~/.fengyu secrets, ... to
            // every plugin), expose only what a JVM worker actually needs to launch:
            //   - the OS/runtime trees a JDK and native libs resolve from (/usr, /bin, /lib, /etc)
            //   - the JDK itself (java.home — may live under $HOME on some distros, so bind JUST it,
            //     never the whole home)
            //   - the plugin's own read-only package directory
            //   - any classpath roots the worker command itself references (so a worker whose deps
            //     live outside the package — e.g. a dev classpath — still launches; production
            //     plugins ship their jars inside the package, so this adds nothing for them)
            // The user home, other plugins, and ~/.fengyu stay invisible. Plugin-owned writable
            // roots (plugin-data, worker tmp, authorized FileRefs) are bound read-write.
            List<String> command = new ArrayList<>();
            command.add("bwrap");
            command.add("--die-with-parent");
            command.add("--new-session");
            for (String ro : List.of("/usr", "/bin", "/lib", "/lib64", "/etc")) {
                appendReadOnlyBind(command, Path.of(ro), "--ro-bind");
            }
            command.add("--proc");
            command.add("/proc");
            command.add("--dev");
            command.add("/dev");
            // Establish the private /tmp before mounting any narrower paths. JUnit and other
            // callers commonly place plugin roots/data below the host /tmp; mounting tmpfs after
            // those binds hides them and makes --chdir fail inside the sandbox.
            command.add("--tmpfs");
            command.add("/tmp");
            // Keep the JVM's logical java.home as the mount destination. GitHub's Temurin setup
            // exposes /usr/lib/jvm/... as a symlink into /opt/hostedtoolcache; binding only the
            // resolved source under /opt leaves the absolute launcher path under /usr unusable.
            Path javaHome = Path.of(System.getProperty("java.home", "")).toAbsolutePath().normalize();
            appendReadOnlyBindAt(command, javaHome, javaHome, "--ro-bind");
            appendReadOnlyBind(command, workdir.toAbsolutePath().normalize(), "--ro-bind");
            for (Path cp : classpathRoots(raw)) {
                appendReadOnlyBind(command, cp, "--ro-bind");
            }
            for (Path root : normalizedExisting(readableRoots)) {
                command.add("--ro-bind");
                command.add(root.toString());
                command.add(root.toString());
            }
            for (Path root : normalizedExisting(writableRoots)) {
                command.add("--bind");
                command.add(root.toString());
                command.add(root.toString());
            }
            if (!allowNetwork) command.add("--unshare-net");
            command.add("--chdir");
            command.add(workdir.toAbsolutePath().normalize().toString());
            command.add("--");
            command.addAll(raw);
            return new Launch(command, backend);
        }

        // macOS sandbox-exec: deny-sensitive. A strict deny-default profile breaks a JVM launch on
        // macOS (the JDK reads/writes under ~/Library for caches/preferences and needs broad system
        // access to even start). Instead, start from (allow default) so the worker launches, then
        // DENY READ of the genuinely sensitive host paths a plugin has no business seeing: the
        // user's SSH/AWS/gcloud/cloud credentials, the FengYu runtime root (host DB, config, logs,
        // other plugins' data), and sibling plugin packages. Writes are denied everywhere except
        // the plugin-owned roots (the package itself stays read-only via the writableRoots list —
        // the host no longer adds the package dir to it, P0-2(a)). This satisfies the review's
        // intent (a plugin must not read host secrets) on a platform where a JVM cannot survive a
        // true deny-default.
        String home = System.getProperty("user.home", "");
        StringBuilder profile = new StringBuilder("(version 1)\n(allow default)\n");
        if (!home.isBlank()) {
            for (String secret : List.of(".ssh", ".aws", ".config/gcloud", ".config/github-copilot",
                    ".gnupg", ".docker", ".kube")) {
                profile.append("(deny file-read* (subpath ")
                        .append(quoted(Path.of(home, secret).toString())).append("))\n");
            }
        }
        // The FengYu runtime root holds the host DB, config, per-plugin data, and logs. Deny a plugin
        // from reading the SENSITIVE subdirs (config, database, logs, skills, all plugin-data) rather
        // than the whole runtime root. The plugin's own package lives under <root>/plugins/<id>, so a
        // whole-root deny forced a fragile deny-then-re-allow that broke the JVM: on macOS the worker
        // JVM could not load backend/worker.jar's Main-Class when the package sat under the denied
        // root (java -jar failed with ClassNotFoundException even though the jar was readable), while
        // temp-located plugins escaped only via the /var ↔ /private/var realpath mismatch. Denying the
        // sensitive leaves keeps the protection that matters (host DB/config/logs/secrets, other
        // plugins' data) and leaves the package readable. macOS is already a reduced-isolation
        // (allow-default) platform, so this is the correct granularity here; a plugin may read a
        // sibling's package, but never its data, the host DB, config, or logs.
        Path runtimeRoot = fan.summer.fengyu.runtime.RuntimePaths.root();
        for (Path sensitive : new Path[] {
                fan.summer.fengyu.runtime.RuntimePaths.configDirectory(runtimeRoot),
                fan.summer.fengyu.runtime.RuntimePaths.databaseDirectory(runtimeRoot),
                fan.summer.fengyu.runtime.RuntimePaths.logDirectory(runtimeRoot),
                fan.summer.fengyu.runtime.RuntimePaths.skillDirectory(runtimeRoot),
        }) {
            Path resolved = realPath(sensitive);
            if (Files.isDirectory(resolved)) {
                profile.append("(deny file-read* (subpath ")
                        .append(quoted(resolved.toString())).append("))\n");
            }
        }
        // sandbox-exec deny rules take precedence over allows, so denying the plugin-data parent
        // and then allowing this plugin's own directory still blocks SQLite/native temp loading.
        // Deny only sibling plugin data directories; the caller-owned writable root remains usable.
        Path pluginDataRoot = realPath(fan.summer.fengyu.runtime.RuntimePaths.pluginDataDirectory(runtimeRoot));
        if (Files.isDirectory(pluginDataRoot)) {
            List<Path> writable = normalizedExisting(writableRoots);
            try (var entries = Files.list(pluginDataRoot)) {
                entries.filter(Files::isDirectory)
                    .map(ProcessSandbox::realPath)
                    .filter(candidate -> writable.stream().noneMatch(root -> root.startsWith(candidate)))
                    .forEach(candidate -> profile.append("(deny file-read* (subpath ")
                        .append(quoted(candidate.toString())).append("))\n"));
            } catch (java.io.IOException ignored) {
                // A disappearing sibling directory is harmless; writes remain denied by default.
            }
        }
        // Writes are denied by default; only the plugin-owned roots may be written.
        profile.append("(deny file-write*)\n");
        for (Path root : normalizedExisting(writableRoots)) {
            // Each writable root is both writable AND readable (it was denied above as a runtime-root
            // subpath; the plugin must still read its own data/tmp dirs).
            profile.append("(allow file-read* (subpath ")
                    .append(quoted(root.toString())).append("))\n");
            profile.append("(allow file-write* (subpath ")
                    .append(quoted(root.toString())).append("))\n");
        }
        for (Path root : normalizedExisting(readableRoots)) {
            profile.append("(allow file-read* (subpath ")
                    .append(quoted(root.toString())).append("))\n");
        }
        // The plugin's own package dir must remain READABLE (it's under the runtime root, denied above).
        appendReadSubpath(profile, workdir.toAbsolutePath().normalize());
        // System tmp is needed for JVM/worker scratch files.
        profile.append("(allow file-write* (subpath \"/tmp\"))\n");
        profile.append("(allow file-write* (subpath \"/private/tmp\"))\n");
        if (!allowNetwork) {
            profile.append("(deny network*)\n");
        }
        List<String> command = new ArrayList<>();
        command.add("sandbox-exec");
        command.add("-p");
        command.add(profile.toString());
        command.addAll(raw);
        return new Launch(command, backend);
    }

    private static List<Path> normalizedExisting(List<Path> roots) {
        if (roots == null) return List.of();
        return roots.stream()
                .filter(path -> path != null && Files.exists(path))
                .map(ProcessSandbox::realPath)
                .distinct()
                .toList();
    }

    /**
     * Paths the worker command references for its classpath, so the sandbox can grant them read
     * access without exposing the whole host. Scans {@code raw} for JVM {@code -cp}/{@code -classpath}
     * arguments (path-separator-split) and {@code -jar} arguments, plus the executable's own
     * directory (for a worker launched by a script next to its jars). Production plugins ship their
     * dependencies inside the package directory (already granted read), so this typically adds
     * nothing for them; it exists so a worker whose deps legitimately resolve outside the package
     * still launches under the tightened view.
     */
    static List<Path> classpathRoots(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        java.util.List<Path> roots = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            String arg = raw.get(i);
            if (("-cp".equals(arg) || "-classpath".equals(arg) || "--class-path".equals(arg))
                    && i + 1 < raw.size()) {
                for (String entry : raw.get(++i).split(java.io.File.pathSeparator)) {
                    addIfReadable(roots, Path.of(entry));
                }
            } else if (("-jar".equals(arg) || "--jar".equals(arg)) && i + 1 < raw.size()) {
                addIfReadable(roots, Path.of(raw.get(++i)));
            }
        }
        return roots.stream().distinct().toList();
    }

    private static void addIfReadable(java.util.List<Path> roots, Path path) {
        if (path == null) return;
        Path resolved = realPath(path);
        if (Files.isReadable(resolved)) roots.add(resolved);
    }

    /** Resolve a path to its real (symlink-followed) absolute form. */
    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (java.io.IOException ignored) {
            return path.toAbsolutePath().normalize();
        }
    }

    /** Append a read-only bind for {@code path} to a bwrap command list (no-op if missing). */
    static void appendReadOnlyBind(List<String> command, Path path, String flag) {
        appendReadOnlyBindAt(command, path, path, flag);
    }

    /** Bind a resolved source at the caller-visible destination (important for symlinked runtimes). */
    static void appendReadOnlyBindAt(List<String> command, Path source, Path destination, String flag) {
        if (source == null || destination == null) return;
        Path resolved = realPath(source);
        if (!Files.exists(resolved)) return;
        command.add(flag);
        command.add(resolved.toString());
        command.add(destination.toAbsolutePath().normalize().toString());
    }

    /** Append an {@code (allow file-read* (subpath "<path>"))} line to a macOS profile. */
    private static void appendReadSubpath(StringBuilder profile, Path path) {
        if (path == null) return;
        Path resolved = realPath(path);
        if (!Files.exists(resolved)) return;
        profile.append("(allow file-read* (subpath ").append(quoted(resolved.toString())).append("))\n");
    }

    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Backend detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux") && executableOnPath("bwrap")) return Backend.BUBBLEWRAP;
        if (os.contains("mac") && Files.isExecutable(Path.of("/usr/bin/sandbox-exec"))) {
            return Backend.SANDBOX_EXEC;
        }
        if (os.contains("win") && WindowsJobSandbox.isAvailable()) return Backend.WINDOWS_JOB;
        return Backend.NONE;
    }

    private static boolean executableOnPath(String name) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) return false;
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (!entry.isBlank() && Files.isExecutable(Path.of(entry, name))) return true;
        }
        return false;
    }
}
