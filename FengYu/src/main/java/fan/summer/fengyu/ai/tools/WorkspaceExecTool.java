package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.ai.AiStreamCallback;
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
 *
 * <p>{@code interactive=true} flips the tool into session mode: the process keeps its stdin
 * open past the call and {@code write_stdin} feeds/polls/terminates it by session id —
 * dev servers, watchers, and REPLs become drivable instead of timing out.</p>
 */
@Component
public class WorkspaceExecTool implements FengYuTool, ToolEffectProvider {

    private final fan.summer.fengyu.ai.sandbox.AgentSandboxManager sandbox;

    static final long DEFAULT_TIMEOUT_SECONDS = 60;
    /** Matches {@code execute_command}'s ceiling — real builds (mvn/cargo/gradle) run
     *  past the old 180s cap; long-running processes belong in interactive sessions. */
    static final long MAX_TIMEOUT_SECONDS = 600;
    static final int MAX_OUTPUT_CHARS = 64_000;

    // The readonly whitelist itself now lives in ExecPolicy's builtin rules (data, not
    // code — user-extensible via ~/.fengyu/ai/rules); what stays here is the STRUCTURAL
    // verifier: flags and operands the whitelist cannot see through.

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

    /** Sessions behind {@code workspace_exec interactive=true} + {@code write_stdin}. */
    private final WorkspaceExecSessions sessions = new WorkspaceExecSessions();

    @org.springframework.beans.factory.annotation.Autowired
    public WorkspaceExecTool(fan.summer.fengyu.ai.sandbox.AgentSandboxManager sandbox) {
        this.sandbox = sandbox;
    }

    /** Test/plain construction: default manager (settings resolve to {@code off} = unchanged behavior). */
    public WorkspaceExecTool() {
        this(new fan.summer.fengyu.ai.sandbox.AgentSandboxManager());
    }

    static final long INTERACTIVE_YIELD_SECONDS = 10;

    @Override
    public ToolEffect effectFor(String toolName) {
        return switch (toolName) {
            case "workspace_exec" -> ToolEffect.COMMAND;
            // Feeding stdin to an interactive process is a continuation of the (approved)
            // start command, but it is still new input to a live program — WRITE, not READ.
            case "write_stdin" -> ToolEffect.WRITE;
            default -> null;
        };
    }

    @Tool(name = "workspace_exec",
          description = "Run a shell command INSIDE the attached workspace (cwd defaults to the "
                  + "workspace root). Use this — not execute_command — for project work: builds, "
                  + "tests, git inspection, file listing. Multi-line scripts and heredocs are "
                  + "supported (the whole text runs under one shell). Read-only inspection "
                  + "commands (ls, git status/diff/log, test/build runners with read-only flags) "
                  + "run without approval; anything that writes or reaches the network asks the "
                  + "user. Output is capped; check the exit code in the result. Set "
                  + "interactive=true for long-running commands (dev servers, watchers, REPLs): "
                  + "the call returns a sessionId after ~10s and you continue it with write_stdin.")
    public String workspaceExec(
            @ToolParam(description = "The shell command line(s) to run — a single command or a "
                    + "multi-line script (heredocs included); it all executes under one shell.")
            String command,
            @ToolParam(required = false,
                       description = "Workspace-relative working directory (default: workspace root).")
            String cwd,
            @ToolParam(required = false,
                       description = "Timeout in seconds (default 60, max 600).")
            Integer timeoutSeconds,
            @ToolParam(required = false,
                       description = "Keep the process alive as a session (for dev servers, "
                              + "watchers, REPLs) instead of killing it at the timeout. Returns "
                              + "a sessionId after ~10s; continue with write_stdin. The session "
                              + "is killed after 10 idle minutes.")
            Boolean interactive) {
        try {
            WorkspaceContext.Binding binding = WorkspaceContext.current();
            if (binding == null) {
                return error("This conversation has no workspace attached");
            }
            if (command == null || command.isBlank()) return error("Command must not be blank");
            Path workingDir = cwd == null || cwd.isBlank()
                    ? binding.root()
                    : WorkspacePathPolicy.resolve(binding.root(), cwd);
            if (!Files.isDirectory(workingDir)) {
                return error("Not a directory: " + cwd);
            }

            long timeout = timeoutSeconds == null ? DEFAULT_TIMEOUT_SECONDS
                    : Math.max(1, Math.min(timeoutSeconds, (int) MAX_TIMEOUT_SECONDS));
            // The result's readonly flag uses the SAME jail (exec base = working dir) the
            // approval-side isReadonlyInvocation applies, so the two verdicts agree.
            boolean readonly = isReadonlyCommandLine(command, workingDir);
            List<String> rawCommand = shellPrefix(command);
            ProcessBuilder builder = new ProcessBuilder(rawCommand)
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

            // The sandbox choke point: settings → profile → platform fence. Off-mode keeps
            // the result JSON byte-identical (no sandbox field at all).
            Map<String, String> envSnapshot = new LinkedHashMap<>(builder.environment());
            fan.summer.fengyu.ai.sandbox.AgentSandboxManager.SandboxLaunch sandboxLaunch =
                    sandbox.transform(builder, sandboxProfile(binding.root()));

            if (Boolean.TRUE.equals(interactive)) {
                return startInteractive(binding, command, workingDir, builder, sandboxLaunch);
            }

            Attempt attempt = runAttempt(builder, timeout, command);
            if (attempt.failure() != null) return failureEnvelope(attempt);

            // The attempt loop (codex orchestrator): a fence denial routes into the escape
            // approval — approve retries ONCE unfenced (a real FullAccess run), a rejection
            // answers the model with the denial + the user's feedback and the turn continues.
            if (attempt.exitCode() != 0 && sandboxLaunch.sandboxed()
                    && fan.summer.fengyu.ai.sandbox.SandboxDenialDetector.likelyDenied(
                            attempt.exitCode(), attempt.output())) {
                ChatToolApprovalGate gate = ToolApprovalContext.gate();
                AiStreamCallback approvalCallback = ToolApprovalContext.callback();
                if (gate != null && approvalCallback != null) {
                    ChatToolApprovalGate.Decision escape = gate.awaitEscapeApproval(
                            "workspace_exec", command,
                            fan.summer.fengyu.ai.sandbox.SandboxDenialDetector.denialExcerpt(
                                    attempt.output(), 200),
                            approvalCallback);
                    if (escape.approved()) {
                        ProcessBuilder unfenced = new ProcessBuilder(rawCommand)
                                .directory(workingDir.toFile())
                                .redirectErrorStream(true);
                        unfenced.environment().clear();
                        unfenced.environment().putAll(envSnapshot);
                        Attempt retried = runAttempt(unfenced, timeout, command);
                        if (retried.failure() != null) return failureEnvelope(retried);
                        Map<String, Object> escapedAudit = new LinkedHashMap<>();
                        escapedAudit.put("backend", "none");
                        escapedAudit.put("profile", "danger-full-access");
                        escapedAudit.put("network", "open");
                        escapedAudit.put("sandboxed", false);
                        escapedAudit.put("escaped", true);
                        escapedAudit.put("note",
                                "user approved running this command outside the sandbox");
                        return execResult(retried.exitCode(), retried.output(), readonly,
                                binding.root(), workingDir, escapedAudit);
                    }
                    Map<String, Object> deniedAudit = new LinkedHashMap<>(sandboxLaunch.toAudit());
                    deniedAudit.put("escapeDenied", true);
                    String feedback = escape.feedback() == null || escape.feedback().isBlank()
                            ? "" : "\n[user rejected running it outside the sandbox: "
                            + escape.feedback() + "]";
                    return execResult(attempt.exitCode(),
                            attempt.output() + "\n[sandboxed run was denied by the OS sandbox; "
                            + "the user declined to run it unfenced]" + feedback,
                            readonly, binding.root(), workingDir, deniedAudit);
                }
            }

            return execResult(attempt.exitCode(), attempt.output(), readonly,
                    binding.root(), workingDir,
                    sandboxLaunch.sandboxed() || sandboxLaunch.degraded()
                            ? sandboxLaunch.toAudit() : null);
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
    }

    /**
     * The sandbox profile for this invocation — settings-driven ({@code off} by default);
     * protected so tests can force a fenced tier without touching settings storage. A
     * FULL_ACCESS conversation skips the fence entirely (the codex danger-full-access
     * pairing with {@code AskForApproval::Never}): no fence denials mid-execution, so no
     * escape-approval prompts — "full control" must not interrupt the user.
     */
    protected fan.summer.fengyu.ai.sandbox.PermissionProfile sandboxProfile(Path workspaceRoot) {
        if (fan.summer.fengyu.ai.tools.AiPermissionContext.current()
                == fan.summer.fengyu.ai.tools.AiPermissionMode.FULL_ACCESS) {
            return new fan.summer.fengyu.ai.sandbox.PermissionProfile.FullAccess();
        }
        return fan.summer.fengyu.ai.sandbox.AgentSandboxManager.profileFor(
                fan.summer.fengyu.ai.sandbox.AgentSandboxManager.sandboxMode(), workspaceRoot);
    }

    /** One fenced or unfenced run; the failure string is the tool-error form, else null.
     *  The output is ALWAYS the bounded head+tail capture — never the raw stream. */
    private record Attempt(Integer exitCode, String output, String failure) {}

    private Attempt runAttempt(ProcessBuilder builder, long timeout, String command) {
        try {
            Process process = builder.start();
            // Close stdin: an interactive command must never hang the turn waiting for input.
            process.getOutputStream().close();
            // Drain stdout on a reader thread into a BOUNDED capture: a child producing
            // more than the OS pipe buffer (~64 KB) blocks on write and would never exit
            // if we waited first (the classic waitFor/readAllBytes deadlock), and an
            // unbounded buffer would let a chatty command allocate the host into OOM
            // long before its timeout — memory is capped at MAX_OUTPUT_CHARS, not just
            // the eventual JSON.
            OutputCapture capture = new OutputCapture(MAX_OUTPUT_CHARS);
            Thread reader = Thread.ofVirtual().start(() -> capture.read(process.getInputStream()));
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                WorkspaceExecSessions.killTree(process);
                process.waitFor(5, TimeUnit.SECONDS);
                reader.join(2_000);
                return new Attempt(null, capture.output(),
                        "Command timed out after " + timeout + "s: " + command);
            }
            reader.join(2_000);
            return new Attempt(process.exitValue(), capture.output(), null);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Attempt(null, null, "Failed to run command: " + e.getMessage());
        }
    }

    /**
     * Bounded head+tail capture — the workspace-exec twin of {@code CommandExecuteTool}'s
     * OutputCapture: the first ~3/4 and last ~1/4 of the stream survive, the middle is
     * dropped with a marker, and memory never exceeds the cap however long the command runs.
     */
    private static final class OutputCapture {
        private final int limit;
        private final int headLimit;
        private final int tailLimit;
        private final StringBuilder prefix = new StringBuilder();
        private final StringBuilder tail = new StringBuilder();
        private long totalChars;

        OutputCapture(int limit) {
            this.limit = limit;
            this.headLimit = limit <= 1 ? limit : Math.max(1, limit * 3 / 4);
            this.tailLimit = limit - headLimit;
        }

        synchronized void read(java.io.InputStream input) {
            try (java.io.InputStreamReader reader = new java.io.InputStreamReader(
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
                // process termination can close the stream while the reader is blocked
            }
        }

        synchronized String output() {
            if (!truncated()) return prefix.toString();
            long omitted = totalChars - headLimit - tail.length();
            return prefix.substring(0, Math.min(headLimit, prefix.length()))
                    + "\n…[" + omitted + " characters of output dropped]\n" + tail;
        }

        synchronized boolean truncated() {
            return totalChars > limit;
        }
    }

    /** The exec result JSON with bounded output and the optional sandbox audit. */
    private static String execResult(int exitCode, String output, boolean readonly,
            Path root, Path workingDir, Map<String, Object> sandboxAudit) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", exitCode == 0);
        result.put("exitCode", exitCode);
        result.put("readonly", readonly);
        result.put("directory", WorkspacePathPolicy.display(root, workingDir));
        if (sandboxAudit != null) result.put("sandbox", sandboxAudit);
        if (output.length() > MAX_OUTPUT_CHARS) {
            result.put("output", output.substring(0, MAX_OUTPUT_CHARS)
                    + "\n…[output truncated at " + MAX_OUTPUT_CHARS + " characters]");
            result.put("truncated", true);
        } else {
            result.put("output", output);
        }
        return toJson(result);
    }

    // ── interactive sessions ─────────────────────────────────────────────────────────────

    /**
     * Interactive start path: the process keeps its stdin open and outlives the tool call.
     * When it finishes inside the first yield window the result degrades to a normal exec
     * result (minus the kill-at-timeout semantics); otherwise a session id hands control to
     * {@code write_stdin}.
     */
    private String startInteractive(WorkspaceContext.Binding binding, String command,
            Path workingDir, ProcessBuilder builder,
            fan.summer.fengyu.ai.sandbox.AgentSandboxManager.SandboxLaunch sandboxLaunch) {
        try {
            Process process = builder.start();
            WorkspaceExecSessions.Session session =
                    sessions.start(binding.conversationId(), binding.root(), process);
            boolean exited = sessions.awaitExit(session, INTERACTIVE_YIELD_SECONDS * 1000L);
            String output = sessions.takePending(session);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("interactive", true);
            result.put("sessionId", session.id);
            result.put("directory", WorkspacePathPolicy.display(binding.root(), workingDir));
            if (sandboxLaunch.sandboxed() || sandboxLaunch.degraded()) {
                result.put("sandbox", sandboxLaunch.toAudit());
            }
            result.put("exited", exited);
            result.put("success", !exited || (session.exitCode != null && session.exitCode == 0));
            if (exited) result.put("exitCode", session.exitCode);
            result.put("output", boundedOutput(output));
            if (!exited) {
                result.put("note", "Still running. Poll output or feed input with write_stdin "
                        + "(sessionId '" + session.id + "'); the session is killed after "
                        + "10 idle minutes.");
            }
            return toJson(result);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return error("Failed to start interactive command: " + e.getMessage());
        }
    }

    @Tool(name = "write_stdin",
          description = "Continue an interactive workspace_exec session (interactive=true): "
                  + "write text to its stdin, wait for new output, or terminate it. Returns "
                  + "only the output produced since the previous call. Include the newline "
                  + "yourself when chars should press Enter. Sessions are killed automatically "
                  + "after 10 idle minutes.")
    public String writeStdin(
            @ToolParam(description = "Session id returned by workspace_exec interactive=true.")
            String sessionId,
            @ToolParam(required = false,
                       description = "Text to write to the process stdin (include \\n for Enter).")
            String chars,
            @ToolParam(required = false,
                       description = "Seconds to wait for new output (default 2, max 60).")
            Integer waitSeconds,
            @ToolParam(required = false,
                       description = "Terminate the process (default false).")
            Boolean terminate) {
        try {
            WorkspaceContext.Binding binding = WorkspaceContext.current();
            if (binding == null) return error("This conversation has no workspace attached");
            sessions.sweep();
            WorkspaceExecSessions.Session session =
                    sessions.get(sessionId, binding.conversationId(), binding.root());
            if (session == null) {
                return error("Unknown session id for this workspace: " + sessionId
                        + " (sessions are killed after 10 idle minutes)");
            }
            if (Boolean.TRUE.equals(terminate)) {
                sessions.kill(session);
                sessions.awaitExit(session, 3_000);
            } else {
                sessions.write(session, chars);
            }

            long waitMillis = (waitSeconds == null ? 2 : Math.max(0, Math.min(waitSeconds, 60)))
                    * 1000L;
            long deadline = System.currentTimeMillis() + waitMillis;
            String output;
            while (true) {
                output = sessions.takePending(session);
                if (!output.isEmpty() || session.exited
                        || System.currentTimeMillis() >= deadline) {
                    break;
                }
                Thread.sleep(50);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("sessionId", session.id);
            result.put("exited", session.exited);
            if (session.exited) result.put("exitCode", session.exitCode);
            result.put("output", boundedOutput(output));
            return toJson(result);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return error(e.getMessage() == null ? "write_stdin failed" : e.getMessage());
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
    }

    private static String boundedOutput(String output) {
        if (output.length() <= MAX_OUTPUT_CHARS) return output;
        return output.substring(0, MAX_OUTPUT_CHARS)
                + "\n…[output truncated at " + MAX_OUTPUT_CHARS + " characters]";
    }

    /** A failed attempt (timeout) still carries whatever the command managed to print —
     *  the bounded partial log usually diagnoses the hang. */
    private static String failureEnvelope(Attempt attempt) {
        if (attempt.output() == null || attempt.output().isEmpty()) return error(attempt.failure());
        Map<String, Object> timedOut = new LinkedHashMap<>();
        timedOut.put("success", false);
        timedOut.put("error", attempt.failure());
        timedOut.put("timedOut", true);
        timedOut.put("output", boundedOutput(attempt.output()));
        return toJson(timedOut);
    }

    /**
     * True when the whole command line is a verifiably read-only invocation: every
     * shell-chain segment starts with a whitelisted executable, carries only safe
     * arguments, and references no shell metacharacters. Package-private — the approval
     * policy consults this per invocation. Without a workspace base the check is lexical
     * only; pass the exec base (workspace root or resolved cwd) for the symlink jail.
     */
    static boolean isReadonlyCommandLine(String command) {
        return isReadonlyCommandLine(command, null);
    }

    static boolean isReadonlyCommandLine(String command, Path base) {
        if (command == null || command.isBlank()) return false;
        if (UNSAFE_INVOCATION.matcher(command).find()) return false;
        for (String segment : command.split("&&|\\|\\||\\|")) {
            if (!segmentIsReadonly(segment.trim(), base)) return false;
        }
        return true;
    }

    /**
     * Policy entry point from {@link ToolApprovalPolicy}: reads the JSON args envelope.
     * The exec base is the attached workspace root (or the resolved {@code cwd} argument),
     * so the operand jail below sees through symlinks exactly like the file tools.
     */
    static boolean isReadonlyInvocation(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return false;
        try {
            Map<?, ?> parsed = JSON.readValue(argumentsJson, Map.class);
            if (!(parsed.get("command") instanceof String command)) return false;
            WorkspaceContext.Binding binding = WorkspaceContext.current();
            if (binding == null) return isReadonlyCommandLine(command);
            Path base;
            try {
                base = parsed.get("cwd") instanceof String cwd && !cwd.isBlank()
                        ? WorkspacePathPolicy.resolve(binding.root(), cwd)
                        : binding.root();
            } catch (RuntimeException escapingCwd) {
                return false; // a cwd that escapes the workspace is never readonly
            }
            return isReadonlyCommandLine(command, base);
        } catch (Exception malformed) {
            return false;
        }
    }

    /** The loaded exec policy; builtin until the user rules dir is read (then refreshable). */
    private static volatile fan.summer.fengyu.ai.sandbox.ExecPolicy EXEC_POLICY =
            fan.summer.fengyu.ai.sandbox.ExecPolicy.builtin();
    private static volatile boolean userRulesLoaded = false;

    static fan.summer.fengyu.ai.sandbox.ExecPolicy execPolicy() {
        if (!userRulesLoaded) {
            synchronized (WorkspaceExecTool.class) {
                if (!userRulesLoaded) {
                    EXEC_POLICY = fan.summer.fengyu.ai.sandbox.ExecPolicy.load(
                            fan.summer.fengyu.ai.sandbox.ExecPolicy.DEFAULT_RULES_DIR);
                    userRulesLoaded = true;
                }
            }
        }
        return EXEC_POLICY;
    }

    /** Reloads the exec policy after an amend wrote a rule file. */
    static void reloadExecPolicy() {
        synchronized (WorkspaceExecTool.class) {
            EXEC_POLICY = fan.summer.fengyu.ai.sandbox.ExecPolicy.load(
                    fan.summer.fengyu.ai.sandbox.ExecPolicy.DEFAULT_RULES_DIR);
            userRulesLoaded = true;
        }
    }

    private static boolean segmentIsReadonly(String segment, Path base) {
        if (segment.isEmpty()) return false;
        String[] words = segment.split("\\s+");
        String executable = basename(words[0].toLowerCase(Locale.ROOT));
        // Bare `git` prints usage — readonly (the engine's git rule needs a subcommand).
        if ("git".equals(executable) && words.length < 2) return true;
        // The rule engine decides at the token level (builtin migrated whitelist +
        // ~/.fengyu/ai/rules; strictest match wins, unmatched asks).
        if (execPolicy().decide(List.of(words)) != fan.summer.fengyu.ai.sandbox.ExecPolicy.Decision.ALLOW) {
            return false;
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
                // Symlink jail: a RELATIVE operand inside the workspace can still name a
                // link (or a path through linked directories) whose real target lives
                // outside it — the same escape read_file's WorkspacePathPolicy collapses.
                // Auto-approval must not follow it out.
                if (base != null && !operandResolvesInsideBase(word, base)) return false;
            }
        }
        return true;
    }

    /**
     * True when {@code word}, resolved against the exec base, cannot reach outside the
     * base through symlinks — mirrors {@code WorkspacePathPolicy}'s containment check for
     * the readonly whitelist. A path that does not exist yet has nothing to collapse (the
     * lexical checks above already decided it); an unresolvable existing path fails closed.
     */
    private static boolean operandResolvesInsideBase(String word, Path base) {
        // A glob expands INSIDE the shell, after this check: it can name an in-workspace
        // symlink that points out, so an unexpanded pattern cannot be proven safe — it
        // fails closed (approval) instead of being auto-approved as a non-existing path.
        if (word.indexOf('*') >= 0 || word.indexOf('?') >= 0 || word.indexOf('[') >= 0) {
            return false;
        }
        Path candidate;
        try {
            candidate = base.resolve(word).normalize();
        } catch (java.nio.file.InvalidPathException invalid) {
            return false;
        }
        if (!Files.exists(candidate)) return true;
        try {
            return candidate.toRealPath().startsWith(base.toRealPath());
        } catch (IOException unresolved) {
            return false;
        }
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
