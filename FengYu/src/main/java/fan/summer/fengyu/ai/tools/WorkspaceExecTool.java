package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.ai.FengYuTool;
import fan.summer.fengyu.ai.workspace.WorkspaceContext;
import fan.summer.fengyu.ai.workspace.WorkspacePathPolicy;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Workspace-jailed shell tool ({@code workspace_exec}) — the coding-workspace counterpart of
 * the global {@code execute_command}: the process always runs with its working directory
 * inside the attached workspace root, with a sanitized environment and bounded output.
 *
 * <p>Commands on the {@link #READONLY_COMMANDS} whitelist (verified per invocation —
 * shell-chain aware) auto-run without approval in every permission mode, mirroring
 * terminal coding-agent practice: {@code ls}, {@code git status}, test runners and builds
 * are inspections; anything else keeps the ordinary COMMAND approval path.</p>
 */
@Component
public class WorkspaceExecTool implements FengYuTool, ToolEffectProvider {

    static final long DEFAULT_TIMEOUT_SECONDS = 60;
    static final long MAX_TIMEOUT_SECONDS = 180;
    static final int MAX_OUTPUT_CHARS = 64_000;

    /**
     * Executables that are read-only BY CONSTRUCTION (no flag or operand form writes files,
     * spawns shells, or reaches the network). Deliberately excludes interpreters
     * (python/node), package managers (npm/yarn), build/test runners (mvn/gradle/pytest…)
     * and stream editors ({@code sed -i}, {@code awk "print > f"}) — those all have trivially
     * reachable write forms, so they keep the ordinary COMMAND approval path; the user can
     * still grant them for the session via the approval card's "always allow".
     */
    private static final Set<String> READONLY_COMMANDS = Set.of(
            "ls", "pwd", "echo", "cat", "head", "tail", "wc", "find", "grep", "rg",
            "sort", "diff", "file", "stat", "du", "df", "tree", "which",
            "date", "basename", "dirname", "realpath", "md5sum", "sha1sum", "sha256sum",
            "cut", "tr", "column", "jq", "git");

    /** git subcommands that only read. Everything else git can do writes or reaches out. */
    private static final Set<String> GIT_READONLY_SUBCOMMANDS = Set.of(
            "status", "diff", "log", "show", "blame", "rev-parse", "ls-files", "ls-tree",
            "shortlog", "describe", "name-rev", "count-objects");

    /** find flags that mutate — an otherwise-readonly find must not carry any of these. */
    private static final Set<String> FIND_MUTATING_FLAGS = Set.of(
            "-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint", "-fprint0", "-fprintf", "-fls");

    /**
     * Flags whose NEXT operand (or {@code =}-embedded value) names an output file —
     * {@code git/diff --output[=]f}, {@code tree -o f}. Scoped per executable because
     * {@code grep -o} (only-matching) is a plain read-only flag.
     */
    private static final Set<String> OUTPUT_FLAG_EXECUTABLES = Set.of("git", "diff", "tree", "sort");
    private static final Set<String> OUTPUT_FLAGS = Set.of(
            "--output", "--output-indicator-context", "--output-indicator-new",
            "--output-indicator-old", "-o");

    /**
     * The readonly whitelist only covers invocations the policy can fully verify. Shell
     * control characters that hide behavior — separators, redirection, substitution,
     * variable expansion, single {@code &} backgrounding — trip the fallback to the
     * ordinary approval path. {@code &&}/{@code ||}/{@code |} chains are NOT unsafe per
     * se: they are split and EVERY segment must independently pass the whitelist below.
     */
    private static final Pattern UNSAFE_INVOCATION = Pattern.compile(
            "[;<>`]|\n|\r|(?<!&)&(?!&)|\\$\\(|\\$\\{|\\$[A-Za-z_]");

    /** Windows drive roots (C:\, C:/x) are absolute paths too. */
    private static final Pattern DRIVE_ROOT = Pattern.compile("^[A-Za-z]:.*");

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public ToolEffect effectFor(String toolName) {
        return "workspace_exec".equals(toolName) ? ToolEffect.COMMAND : null;
    }

    @Tool(name = "workspace_exec",
          description = "Run a shell command INSIDE the attached workspace (cwd defaults to the "
                  + "workspace root). Use this — not execute_command — for project work: builds, "
                  + "tests, git inspection, file listing. Read-only inspection commands (ls, git "
                  + "status/diff/log, test/build runners with read-only flags) run without "
                  + "approval; anything that writes or reaches the network asks the user. "
                  + "Output is capped; check the exit code in the result.")
    public String workspaceExec(
            @ToolParam(description = "The shell command line to run.") String command,
            @ToolParam(required = false,
                       description = "Workspace-relative working directory (default: workspace root).")
            String cwd,
            @ToolParam(required = false,
                       description = "Timeout in seconds (default 60, max 180).")
            Integer timeoutSeconds) {
        try {
            WorkspaceContext.Binding binding = WorkspaceContext.current();
            if (binding == null) {
                return error("This conversation has no workspace attached");
            }
            if (command == null || command.isBlank()) return error("Command must not be blank");
            if (command.split("\n", -1).length > 1) {
                return error("One command per call (no newline chains)");
            }
            Path workingDir = cwd == null || cwd.isBlank()
                    ? binding.root()
                    : WorkspacePathPolicy.resolve(binding.root(), cwd);
            if (!Files.isDirectory(workingDir)) {
                return error("Not a directory: " + cwd);
            }

            long timeout = timeoutSeconds == null ? DEFAULT_TIMEOUT_SECONDS
                    : Math.max(1, Math.min(timeoutSeconds, (int) MAX_TIMEOUT_SECONDS));
            boolean readonly = isReadonlyCommandLine(command);
            ProcessBuilder builder = new ProcessBuilder(shellPrefix(command))
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true);
            // Minimal, explicit environment: inherit PATH/HOME/LANG/TERM plus common build
            // variables so project toolchains work, but never credentials or host secrets.
            Map<String, String> env = builder.environment();
            env.clear();
            for (String key : List.of("PATH", "HOME", "LANG", "LC_ALL", "TERM",
                    "JAVA_HOME", "NODE_HOME", "PYTHONPATH", "PYTHONUNBUFFERED",
                    "CARGO_HOME", "RUSTUP_HOME", "GOPATH", "GOPROXY", "GOLANG_PROTOBUF_REGISTRATION_MISMATCH",
                    "http_proxy", "https_proxy", "no_proxy", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY")) {
                String value = System.getenv(key);
                if (value != null) env.put(key, value);
            }
            // Windows runtime essentials: cmd.exe and most Win32 programs (crypto,
            // temp-dir resolution, executable lookup) misbehave or fail without these.
            for (String key : List.of("SystemRoot", "SystemDrive", "TEMP", "TMP",
                    "PATHEXT", "COMSPEC", "USERPROFILE", "LOCALAPPDATA")) {
                String value = System.getenv(key);
                if (value != null) env.put(key, value);
            }

            Process process;
            int exitCode;
            String output;
            try {
                process = builder.start();
                // Close stdin: an interactive command must never hang the turn waiting for input.
                process.getOutputStream().close();
                // Drain stdout on a reader thread: a child producing more than the OS pipe
                // buffer (~64 KB) blocks on write and would never exit if we waited first —
                // the classic waitFor/readAllBytes deadlock.
                java.util.concurrent.atomic.AtomicReference<byte[]> captured =
                        new java.util.concurrent.atomic.AtomicReference<>(new byte[0]);
                java.util.concurrent.atomic.AtomicReference<IOException> readFailure =
                        new java.util.concurrent.atomic.AtomicReference<>();
                Thread reader = Thread.ofVirtual().start(() -> {
                    try {
                        captured.set(process.getInputStream().readAllBytes());
                    } catch (IOException e) {
                        readFailure.set(e);
                    }
                });
                boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return error("Command timed out after " + timeout + "s: " + command);
                }
                reader.join(2_000);
                if (readFailure.get() != null) {
                    return error("Failed to read command output: " + readFailure.get().getMessage());
                }
                exitCode = process.exitValue();
                output = new String(captured.get(), StandardCharsets.UTF_8);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return error("Failed to run command: " + e.getMessage());
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", exitCode == 0);
            result.put("exitCode", exitCode);
            result.put("readonly", readonly);
            result.put("directory", WorkspacePathPolicy.display(binding.root(), workingDir));
            if (output.length() > MAX_OUTPUT_CHARS) {
                result.put("output", output.substring(0, MAX_OUTPUT_CHARS)
                        + "\n…[output truncated at " + MAX_OUTPUT_CHARS + " characters]");
                result.put("truncated", true);
            } else {
                result.put("output", output);
            }
            return toJson(result);
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
    }

    /**
     * True when the whole command line is a verifiably read-only invocation: every
     * shell-chain segment starts with a whitelisted executable, carries only safe
     * arguments, and references no shell metacharacters. Package-private — the approval
     * policy consults this per invocation.
     */
    static boolean isReadonlyCommandLine(String command) {
        if (command == null || command.isBlank()) return false;
        if (UNSAFE_INVOCATION.matcher(command).find()) return false;
        for (String segment : command.split("&&|\\|\\||\\|")) {
            if (!segmentIsReadonly(segment.trim())) return false;
        }
        return true;
    }

    /** Policy entry point from {@link ToolApprovalPolicy}: reads the JSON args envelope. */
    static boolean isReadonlyInvocation(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return false;
        try {
            Map<?, ?> parsed = JSON.readValue(argumentsJson, Map.class);
            return parsed.get("command") instanceof String command
                    && isReadonlyCommandLine(command);
        } catch (Exception malformed) {
            return false;
        }
    }

    private static boolean segmentIsReadonly(String segment) {
        if (segment.isEmpty()) return false;
        String[] words = segment.split("\\s+");
        String executable = basename(words[0].toLowerCase(Locale.ROOT));
        if (!READONLY_COMMANDS.contains(executable)) return false;
        if ("git".equals(executable)) {
            // Bare `git` prints usage; anything beyond must be a read-only subcommand.
            if (words.length < 2) return true;
            String subcommand = words[1].toLowerCase(Locale.ROOT);
            if (!GIT_READONLY_SUBCOMMANDS.contains(subcommand)) return false;
        }
        for (int i = 1; i < words.length; i++) {
            String word = words[i];
            if ("find".equals(executable) && FIND_MUTATING_FLAGS.contains(word)) return false;
            // `date -s/--set` writes the system clock; every other date form just prints.
            if ("date".equals(executable) && (word.equals("-s") || word.equals("--set"))) {
                return false;
            }
            // Any flag=value spelling is rejected outright: the whitelist cannot see what
            // the value writes (git diff --output=/any/path, env-style assignments, …).
            if (word.startsWith("-") && word.indexOf('=') >= 0) return false;
            // Output-file flags in either spelling (--output f / -o f) write files — only
            // the output-capable executables; grep -o stays a read-only matcher.
            if (OUTPUT_FLAG_EXECUTABLES.contains(executable) && OUTPUT_FLAGS.contains(word)) {
                return false;
            }
            // rg --pre <cmd> executes the given command per searched file — arbitrary code
            // execution dressed as a search flag.
            if ("rg".equals(executable) && word.startsWith("--pre")) {
                return false;
            }
            // Operands must stay relative to the jailed cwd: absolute, home, and
            // parent-escaping paths would read or write outside the workspace root
            // (cat ~/.ssh/id_rsa, git diff --output /tmp/x). Backslash ESCAPES are removed
            // first — the shell drops them, so `.\./etc` IS `../etc`; replacing them with
            // slashes would mask the escape (`././etc`). Windows drive roots checked too.
            if (!word.startsWith("-")) {
                // POSIX shells drop backslashes (escapes), Windows treats them as path
                // separators — the operand must stay jailed under EITHER reading, so a
                // `..` segment (or an absolute operand) cannot hide in the other one.
                for (String normalized : List.of(word.replace("\\", ""), word.replace("\\", "/"))) {
                    if (normalized.startsWith("/") || normalized.startsWith("~")
                            || normalized.startsWith("..") || normalized.contains("=/")
                            || DRIVE_ROOT.matcher(normalized).matches()) {
                        return false;
                    }
                    for (String part : normalized.split("/")) {
                        if (part.equals("..")) return false;
                    }
                }
            }
        }
        return true;
    }

    private static String basename(String word) {
        int slash = Math.max(word.lastIndexOf('/'), word.lastIndexOf('\\'));
        return slash >= 0 ? word.substring(slash + 1) : word;
    }

    /** Runs the command through the platform shell so pipes/quotes parse normally. */
    private static List<String> shellPrefix(String command) {
        String shell = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "cmd.exe" : "/bin/sh";
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? List.of(shell, "/c", command)
                : List.of(shell, "-c", command);
    }

    private static String error(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error", message == null ? "workspace_exec failed" : message);
        return toJson(result);
    }

    private static String toJson(Map<String, Object> result) {
        try {
            return JSON.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"error\":\"tool result serialization failed\"}";
        }
    }
}
