package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.security.ProcessSandbox;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
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
 * Executes a shell command after approval in ordinary chat or a Plan-and-Execute agent step.
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
          description = "Execute a shell command in a working directory. This tool always pauses "
              + "for explicit user approval before running. Native sandboxing is used when "
              + "available; otherwise compatibility mode is reported in the result. Returns JSON "
              + "with the exit code, combined output, sandbox, timeout, and truncation state.")
    public String execute(
            @ToolParam(description = "The exact shell command to execute.") String command,
            @ToolParam(required = false,
                       description = "Working directory. Defaults to the server process directory.")
            String workingDirectory,
            @ToolParam(required = false,
                       description = "Timeout in seconds (default 30, maximum 600).")
            Integer timeoutSeconds,
            @ToolParam(required = false,
                       description = "Maximum captured output characters (default 65536, maximum 262144).")
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
        OutputCapture capture = new OutputCapture(outputLimit);
        boolean timedOut = false;
        boolean networkAllowed = Boolean.TRUE.equals(allowNetwork);

        try {
            ProcessSandbox.Launch launch =
                    sandbox.command(shellCommand(command), workdir, networkAllowed);
            ProcessBuilder builder = new ProcessBuilder(launch.command())
                    .directory(workdir.toFile())
                    .redirectErrorStream(true);
            removeSensitiveEnvironment(builder.environment());
            process = builder.start();

            Process running = process;
            Thread reader = Thread.ofVirtual().name("command-output-reader").start(
                    () -> capture.read(running));

            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                timedOut = true;
                terminate(process);
                process.waitFor(5, TimeUnit.SECONDS);
            }
            reader.join(Duration.ofSeconds(5));
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
            result.put("output", capture.output());
            result.put("truncated", capture.truncated());
            return toJson(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) terminate(process);
            return error("command execution was interrupted");
        } catch (Exception e) {
            if (process != null && process.isAlive()) terminate(process);
            return error("failed to execute command: " + e.getMessage());
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
        Path resolved = directory.toAbsolutePath().normalize();
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

    private static void terminate(Process process) {
        process.toHandle().descendants().forEach(handle -> {
            try {
                handle.destroyForcibly();
            } catch (Exception ignored) {
                // Best effort: the root process is forcibly terminated below.
            }
        });
        process.destroyForcibly();
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
        private final StringBuilder output = new StringBuilder();
        private long totalChars;

        private OutputCapture(int limit) {
            this.limit = limit;
        }

        private void read(Process process) {
            try (InputStreamReader reader = new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) >= 0) {
                    totalChars += read;
                    int remaining = limit - output.length();
                    if (remaining > 0) {
                        output.append(buffer, 0, Math.min(read, remaining));
                    }
                }
            } catch (IOException ignored) {
                // Process termination can close the stream while the reader is blocked.
            }
        }

        private String output() {
            return output.toString();
        }

        private boolean truncated() {
            return totalChars > limit;
        }
    }
}
