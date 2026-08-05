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
     * Returns whether this host can enforce the native process boundary used by AI-authored
     * commands. Permission policy uses this to fail closed instead of treating a regex heuristic
     * as a sandbox on unsupported platforms.
     */
    public static boolean isNativeSandboxAvailable() {
        return detect() != Backend.NONE;
    }

    /**
     * Sandbox an AI-authored shell command. The command may read system files needed by the
     * runtime, but writes are limited to the selected working directory and network is isolated
     * unless the user explicitly approved it.
     */
    public Launch command(List<String> raw, Path workingDirectory, boolean allowNetwork) {
        return wrap(raw, workingDirectory, List.of(workingDirectory), false, allowNetwork);
    }

    /** Explicit full-access profile: run without the native sandbox after the user selected it. */
    public Launch unrestricted(List<String> raw) {
        return new Launch(raw, Backend.NONE);
    }

    /**
     * Sandbox a plugin Worker according to its installed permissions. Plugins declaring
     * {@code files.write} retain broad write compatibility; otherwise writes are limited to the
     * plugin-owned roots. Network is isolated unless declared by the manifest.
     */
    public Launch plugin(List<String> raw, Path pluginRoot, List<Path> writableRoots,
                         boolean broadFileWrite, boolean allowNetwork) {
        if (backend == Backend.NONE) {
            throw new IllegalStateException("Plugin workers require a supported native process sandbox");
        }
        return wrap(raw, pluginRoot, writableRoots, broadFileWrite, allowNetwork);
    }

    private Launch wrap(List<String> raw, Path workdir, List<Path> writableRoots,
                        boolean broadFileWrite, boolean allowNetwork) {
        if (backend == Backend.NONE) return new Launch(raw, backend);
        if (backend == Backend.WINDOWS_JOB) {
            // Job Objects are assigned AFTER the process starts; the command itself is unchanged.
            // The caller passes a long[1] receiver; the hook writes the job handle into it so the
            // caller can later terminate/close the job (see WindowsJobSandbox).
            java.util.function.BiConsumer<Process, long[]> onStarted = (process, handleOut) -> {
                long job = WindowsJobSandbox.createAndConfigureJob();
                WindowsJobSandbox.assign(job, process);
                handleOut[0] = job;
            };
            return new Launch(raw, backend, onStarted);
        }
        if (backend == Backend.BUBBLEWRAP) {
            List<String> command = new ArrayList<>();
            command.add("bwrap");
            command.add("--die-with-parent");
            command.add("--new-session");
            command.add("--ro-bind");
            command.add("/");
            command.add("/");
            command.add("--proc");
            command.add("/proc");
            command.add("--dev-bind");
            command.add("/dev");
            command.add("/dev");
            command.add("--tmpfs");
            command.add("/tmp");
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

        StringBuilder profile = new StringBuilder("(version 1)\n(allow default)\n");
        if (!allowNetwork) profile.append("(deny network*)\n");
        profile.append("(deny file-write*)\n")
                .append("(allow file-write* (subpath \"/tmp\"))\n");
        for (Path root : normalizedExisting(writableRoots)) {
            profile.append("(allow file-write* (subpath ")
                    .append(quoted(root.toString()))
                    .append("))\n");
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

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (java.io.IOException ignored) {
            return path.toAbsolutePath().normalize();
        }
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
