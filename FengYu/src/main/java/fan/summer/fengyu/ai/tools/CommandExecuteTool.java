package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.security.ProcessSandbox;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Executes a shell command under the active permission profile.
 *
 * <p>The process has a bounded runtime and bounded captured output. Potentially sensitive
 * inherited environment variables are removed before launch so an AI-authored command cannot
 * accidentally print host credentials. A native OS sandbox is used where supported; compatibility
 * fallback is disclosed in the result and the mandatory approval gate remains in force.
 */
@Component
public class CommandExecuteTool implements ApprovalRequiredTool {

    static final int DEFAULT_TIMEOUT_SECONDS = 30;
    static final int MAX_TIMEOUT_SECONDS = 600;
    static final int DEFAULT_MAX_OUTPUT_CHARS = 64 * 1024;
    static final int MAX_OUTPUT_CHARS = 256 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ProcessSandbox sandbox;

    public CommandExecuteTool() {
        this(new ProcessSandbox());
    }

    @Autowired
    public CommandExecuteTool(ProcessSandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Tool(name = "execute_command",
          description = "Execute a shell command in a working directory. Ask-for-approval mode "
              + "always pauses; approve-for-me pauses only for risky commands. Native sandboxing is used when "
              + "available; otherwise compatibility mode is reported in the result. Returns JSON "
              + "with the exit code, separated stdout/stderr, backward-compatible combined output, "
              + "sandbox, timeout, and head/tail truncation state.")
    public String execute(
            @ToolParam(description = "The exact shell command to execute.") String command,
            @ToolParam(required = false,
                       description = "Working directory. Defaults to the server process directory.")
            String workingDirectory,
            @ToolParam(required = false,
                       description = "Timeout in seconds (default 30, maximum 600).")
            Integer timeoutSeconds,
            @ToolParam(required = false,
                       description = "Maximum captured characters per stdout/stderr stream "
                           + "(default 65536, maximum 262144).")
            Integer maxOutputChars,
            @ToolParam(required = false,
                       description = "Allow network access inside the native sandbox. Defaults to false.")
            Boolean allowNetwork) {
        if (command == null || command.isBlank()) {
            return error("command must not be blank");
        }

        Path workdir;
        try {
            workdir = resolveWorkingDirectory(workingDirectory);
        } catch (Exception e) {
            return error(e.getMessage());
        }

        int timeout = bounded(timeoutSeconds, DEFAULT_TIMEOUT_SECONDS, 1, MAX_TIMEOUT_SECONDS);
        int outputLimit = bounded(maxOutputChars, DEFAULT_MAX_OUTPUT_CHARS, 1, MAX_OUTPUT_CHARS);
        Process process = null;
        // Mutable receiver shared with the WINDOWS_JOB onStarted hook. Keep the receiver itself for
        // the whole method: a hook may assign a handle and then throw, in which case copying the
        // value only after accept() returns would lose the handle and leak it.
        long[] jobHandle = {0L};
        OutputCapture stdout = new OutputCapture(outputLimit);
        OutputCapture stderr = new OutputCapture(outputLimit);
        boolean timedOut = false;
        boolean fullAccess = AiPermissionContext.current() == AiPermissionMode.FULL_ACCESS;
        boolean networkAllowed = fullAccess || Boolean.TRUE.equals(allowNetwork);

        try {
            ProcessSandbox.Launch launch = fullAccess
                    ? sandbox.unrestricted(shellCommand(command))
                    : sandbox.command(shellCommand(command), workdir, networkAllowed);
            ProcessBuilder builder = new ProcessBuilder(launch.command())
                    .directory(workdir.toFile());
            removeSensitiveEnvironment(builder.environment());
            process = builder.start();
            // WINDOWS_JOB backend assigns the process to a Job Object after start; the hook writes
            // the job handle into handleOut[0]. On other backends onStarted() is null and jobHandle
            // stays 0 (terminate/close then no-op). If the hook throws (create/assign failed), let
            // it propagate — the command must fail to start rather than run un-jailed.
            if (launch.onStarted() != null) {
                launch.onStarted().accept(process, jobHandle);
            }

            Process running = process;
            Thread stdoutReader = Thread.ofVirtual().name("command-stdout-reader").start(
                    () -> stdout.read(running.getInputStream()));
            Thread stderrReader = Thread.ofVirtual().name("command-stderr-reader").start(
                    () -> stderr.read(running.getErrorStream()));

            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                timedOut = true;
                terminate(process, jobHandle[0]);
                jobHandle[0] = 0L;
                process.waitFor(5, TimeUnit.SECONDS);
            }
            stdoutReader.join(Duration.ofSeconds(5));
            stderrReader.join(Duration.ofSeconds(5));
            Integer exitCode = process.isAlive() ? null : process.exitValue();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", !timedOut && exitCode != null && exitCode == 0);
            result.put("command", command);
            result.put("workingDirectory", workdir.toString());
            result.put("exitCode", timedOut ? null : exitCode);
            result.put("timedOut", timedOut);
            result.put("sandboxed", launch.sandboxed());
            result.put("sandboxBackend", launch.backend().id());
            result.put("networkAllowed", networkAllowed);
            result.put("stdout", stdout.output());
            result.put("stderr", stderr.output());
            result.put("stdoutTruncated", stdout.truncated());
            result.put("stderrTruncated", stderr.truncated());
            result.put("output", stdout.output() + stderr.output());
            result.put("truncated", stdout.truncated() || stderr.truncated());
            return toJson(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                terminate(process, jobHandle[0]);
                jobHandle[0] = 0L;
            }
            return error("command execution was interrupted");
        } catch (Error e) {
            // Preserve Error semantics, but never let an onStarted/JNA failure orphan the process
            // or leak a Job Object handle. This mirrors PluginProcessManager's hook cleanup.
            if (process != null) {
                terminate(process, jobHandle[0]);
                awaitTermination(process);
                jobHandle[0] = 0L;
            }
            throw e;
        } catch (Exception e) {
            // Always run the cleanup when a process was started, even if it exited between the
            // failure and this catch. A WINDOWS_JOB hook can write the handle and then throw; the
            // process may already be dead while the kernel handle still needs closing.
            if (process != null) {
                terminate(process, jobHandle[0]);
                awaitTermination(process);
                jobHandle[0] = 0L;
            }
            return error("failed to execute command: " + e.getMessage());
        } finally {
            // Normal completion does not call terminate(), but the Job Object handle is still an
            // owned kernel resource. Close it after output collection/return preparation. On the
            // timeout/error paths the slot was reset to zero after terminate() closed it.
            if (jobHandle[0] != 0L) {
                try { sandbox.closeJobHandle(jobHandle[0]); } catch (RuntimeException ignored) {}
                jobHandle[0] = 0L;
            }
        }
    }

    /** Compatibility overload retained for direct callers and existing plugin tests. */
    public String execute(String command, String workingDirectory,
                          Integer timeoutSeconds, Integer maxOutputChars) {
        return execute(command, workingDirectory, timeoutSeconds, maxOutputChars, false);
    }

    private static Path resolveWorkingDirectory(String value) {
        Path directory = value == null || value.isBlank()
                ? Path.of(System.getProperty("user.dir"))
                : Path.of(value);
        Path resolved;
        try { resolved = directory.toRealPath(); }
        catch (IOException e) { throw new IllegalArgumentException("working directory does not exist: " + directory); }
        if (!Files.isDirectory(resolved)) {
            throw new IllegalArgumentException("working directory does not exist: " + resolved);
        }
        return resolved;
    }

    private static java.util.List<String> shellCommand(String command) {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return java.util.List.of("cmd.exe", "/d", "/s", "/c", command);
        }
        return java.util.List.of("/bin/sh", "-lc", command);
    }

    private static int bounded(Integer value, int defaultValue, int min, int max) {
        if (value == null) return defaultValue;
        return Math.max(min, Math.min(max, value));
    }

    private static void removeSensitiveEnvironment(Map<String, String> environment) {
        environment.keySet().removeIf(CommandExecuteTool::isSensitiveEnvironmentName);
    }

    static boolean isSensitiveEnvironmentName(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.contains("TOKEN")
                || upper.contains("SECRET")
                || upper.contains("PASSWORD")
                || upper.contains("PASSWD")
                || upper.contains("API_KEY")
                || upper.contains("APIKEY")
                || upper.contains("CREDENTIAL")
                || upper.contains("COOKIE")
                || upper.contains("AUTHORIZATION");
    }

    /**
     * Terminate a command process and any descendants. Instance method so it can reach the
     * {@link ProcessSandbox} job-handle API; the two-arg form takes the WINDOWS_JOB handle captured
     * by {@code onStarted} (0 on other backends / NONE). Order: kernel tree-kill via the Job Object
     * (Windows primary, guarded by handle != 0, wrapped so a cleanup failure cannot mask the
     * destroy path) → descendants {@code destroyForcibly} (backstop for macOS/Linux and any
     * grandchildren not tracked by the Job) → {@code destroyForcibly} the root → close the job
     * handle last (KILL_ON_JOB_CLOSE on survivors + kernel-handle release).
     */
    private void terminate(Process process, long jobHandle) {
        if (jobHandle != 0L) {
            try { sandbox.terminateJob(jobHandle); } catch (RuntimeException ignored) {}
        }
        process.toHandle().descendants().forEach(handle -> {
            try {
                handle.destroyForcibly();
            } catch (Exception ignored) {
                // Best effort: the root process is forcibly terminated below.
            }
        });
        process.destroyForcibly();
        if (jobHandle != 0L) {
            try { sandbox.closeJobHandle(jobHandle); } catch (RuntimeException ignored) {}
        }
    }

    /** Briefly wait for a requested force-kill so a failed launch cannot return with a live child. */
    private static void awaitTermination(Process process) {
        try {
            process.onExit().get(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) {
            // Kill has already been requested; no stronger portable primitive is available here.
        }
    }

    /** Defensive single-arg overload for any path that does not own a job handle. */
    private void terminate(Process process) {
        terminate(process, 0L);
    }

    private static String error(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error", message == null ? "command execution failed" : message);
        return toJson(result);
    }

    private static String toJson(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"error\":\"failed to serialize command result\"}";
        }
    }

    private static final class OutputCapture {
        private final int limit;
        private final int headLimit;
        private final int tailLimit;
        private final StringBuilder prefix = new StringBuilder();
        private final StringBuilder tail = new StringBuilder();
        private long totalChars;

        private OutputCapture(int limit) {
            this.limit = limit;
            this.headLimit = limit <= 1 ? limit : Math.max(1, limit * 3 / 4);
            this.tailLimit = limit - headLimit;
        }

        private void read(InputStream input) {
            try (InputStreamReader reader = new InputStreamReader(
                    input, StandardCharsets.UTF_8)) {
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) >= 0) {
                    totalChars += read;
                    int remaining = limit - prefix.length();
                    if (remaining > 0) {
                        prefix.append(buffer, 0, Math.min(read, remaining));
                    }
                    if (tailLimit > 0) {
                        tail.append(buffer, 0, read);
                        if (tail.length() > tailLimit) {
                            tail.delete(0, tail.length() - tailLimit);
                        }
                    }
                }
            } catch (IOException ignored) {
                // Process termination can close the stream while the reader is blocked.
            }
        }

        private String output() {
            if (!truncated()) return prefix.toString();
            long omitted = totalChars - headLimit - tail.length();
            return prefix.substring(0, Math.min(headLimit, prefix.length()))
                    + "\n... [FengYu omitted " + omitted + " characters] ...\n"
                    + tail;
        }

        private boolean truncated() {
            return totalChars > limit;
        }
    }
}
