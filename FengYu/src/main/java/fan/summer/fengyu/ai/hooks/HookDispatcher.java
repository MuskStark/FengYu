package fan.summer.fengyu.ai.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Lifecycle hooks around AI tool use and agent runs, configurable from Settings.
 *
 * <p>The execution contract follows the model proven by terminal agents (grok-build's
 * hooks): hooks run sequentially in configuration order; a blocking
 * {@link HookEvent#PRE_TOOL_USE} hook can deny (or allow) a tool call; observe events
 * never influence execution.
 *
 * <h2>Command hooks</h2>
 * <p>The event envelope is written to the hook process's stdin; the exit code and stdout
 * decide the outcome:
 * <ul>
 *   <li>{@code exit 0} — allow (stdout may carry a gate JSON to allow explicitly).</li>
 *   <li>{@code exit 2} — <b>deny</b>; the first stderr line is the deny reason.</li>
 *   <li>stdout gate JSON {@code {"decision":"deny","reason":"..."}} — deny on ANY exit code
 *       (fail-safe); {@code {"decision":"allow"}} is honored unless the exit code is 2
 *       (exit 2 wins: stdout is not processed on it).</li>
 *   <li>any other exit code, a timeout, or a spawn failure — <b>fail-open</b>: the failure
 *       is logged and the tool call proceeds. FengYu runs local personal tooling where an
 *       induced hook failure is not part of the threat model; blocking every call because
 *       a hook crashed would make the feature a self-inflicted outage.</li>
 * </ul>
 *
 * <h2>HTTP hooks</h2>
 * <p>The same envelope is POSTed as JSON; a response body with a gate JSON decides like
 * stdout above. Non-2xx responses and timeouts fail open. Only point hooks at endpoints
 * you trust — the envelope contains tool arguments.
 */
@Service
public class HookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(HookDispatcher.class);
    private static final int DENY_EXIT_CODE = 2;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_OUTPUT_CHARS = 64_000;

    /**
     * Configured hook timeouts are clamped to {@code [1, 60]} seconds at every parse site
     * (CQ-05): a typo like {@code "timeoutSeconds": 600} must not make every tool call
     * serially wait minutes behind a hook that is never going to answer.
     */
    static final long MIN_HOOK_TIMEOUT_SECONDS = 1;
    static final long MAX_HOOK_TIMEOUT_SECONDS = 60;

    /** Clamps a configured hook timeout (seconds) into the supported window. */
    public static Duration boundedHookTimeout(long timeoutSeconds) {
        return Duration.ofSeconds(Math.min(MAX_HOOK_TIMEOUT_SECONDS,
                Math.max(MIN_HOOK_TIMEOUT_SECONDS, timeoutSeconds)));
    }
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private volatile List<HookDefinition> definitions = List.of();

    /** Replaces the active hook set (called by Settings when the configuration changes). */
    public void update(List<HookDefinition> definitions) {
        List<HookDefinition> safe = new ArrayList<>();
        if (definitions != null) {
            for (HookDefinition definition : definitions) {
                if (definition == null || !definition.enabled()) continue;
                if (definition.event() == null) continue;
                String target = definition.type() == HookDefinition.Type.HTTP
                        ? definition.url() : definition.command();
                if (target == null || target.isBlank()) continue;
                safe.add(definition);
            }
        }
        this.definitions = List.copyOf(safe);
    }

    public List<HookDefinition> definitions() {
        return definitions;
    }

    /** Outcome of a blocking {@link HookEvent#PRE_TOOL_USE} dispatch. */
    public record PreToolDecision(boolean allowed, String denyReason, List<String> executedHooks) {
        static PreToolDecision allow(List<String> executed) {
            return new PreToolDecision(true, null, executed);
        }
    }

    /**
     * Dispatches {@link HookEvent#PRE_TOOL_USE} against every matching hook in config
     * order. The first explicit deny wins; failures fail open (allow).
     */
    public PreToolDecision preToolUse(String toolName, Map<String, Object> toolInput, String runId) {
        List<HookDefinition> hooks = hooksFor(HookEvent.PRE_TOOL_USE, toolName);
        if (hooks.isEmpty()) return PreToolDecision.allow(List.of());
        Map<String, Object> envelope = envelope(HookEvent.PRE_TOOL_USE, toolName, toolInput, runId, null);
        List<String> executed = new ArrayList<>();
        for (HookDefinition hook : hooks) {
            executed.add(hook.name());
            GateOutcome outcome = runHook(hook, envelope);
            if (outcome.deny()) {
                return new PreToolDecision(false, outcome.reason(), executed);
            }
        }
        return PreToolDecision.allow(executed);
    }

    /**
     * Dispatches an observe-only event. Never blocks the caller beyond each hook's own
     * timeout; failures are logged and swallowed (fail-open).
     */
    public void observe(HookEvent event, String toolName, Map<String, Object> toolInput,
                        String runId, Object result) {
        List<HookDefinition> hooks = hooksFor(event, toolName);
        if (hooks.isEmpty()) return;
        Map<String, Object> envelope = envelope(event, toolName, toolInput, runId, result);
        for (HookDefinition hook : hooks) {
            runHook(hook, envelope);
        }
    }

    private List<HookDefinition> hooksFor(HookEvent event, String toolName) {
        List<HookDefinition> matched = new ArrayList<>();
        for (HookDefinition definition : definitions) {
            if (definition.event() != event) continue;
            if (event.matchesTools() && definition.matcher() != null
                    && !definition.matcher().isBlank()) {
                try {
                    Pattern pattern = Pattern.compile(definition.matcher());
                    if (toolName == null || !pattern.matcher(toolName).find()) continue;
                } catch (java.util.regex.PatternSyntaxException badMatcher) {
                    // A bad matcher must never break the tool call: skip the hook
                    // (fail-open) — save-time validation normally rejects these first.
                    log.warn("hook '{}' has an invalid matcher '{}'; skipping",
                            definition.name(), definition.matcher());
                    continue;
                }
            }
            matched.add(definition);
        }
        return matched;
    }

    private Map<String, Object> envelope(HookEvent event, String toolName,
                                         Map<String, Object> toolInput, String runId, Object result) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("hookEventName", event.wireName());
        envelope.put("toolName", toolName);
        envelope.put("toolInput", toolInput == null ? Map.of() : toolInput);
        envelope.put("runId", runId);
        envelope.put("timestamp", java.time.Instant.now().toString());
        if (result != null) envelope.put("toolResult", truncate(String.valueOf(result)));
        return envelope;
    }

    private record GateOutcome(boolean deny, String reason) {
        static final GateOutcome ALLOW = new GateOutcome(false, null);
    }

    private GateOutcome runHook(HookDefinition hook, Map<String, Object> envelope) {
        long started = System.nanoTime();
        try {
            return hook.type() == HookDefinition.Type.HTTP
                    ? runHttp(hook, envelope)
                    : runCommand(hook, envelope);
        } catch (Exception unexpected) {
            log.warn("hook '{}' failed open: {}", hook.name(), unexpected.getMessage());
            return GateOutcome.ALLOW;
        } finally {
            log.debug("hook '{}' ran in {} ms", hook.name(),
                    (System.nanoTime() - started) / 1_000_000);
        }
    }

    private GateOutcome runCommand(HookDefinition hook, Map<String, Object> envelope) throws Exception {
        List<String> command = shellWrap(hook.command());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("FENGYU_HOOK_EVENT", envelope.get("hookEventName").toString());
        if (envelope.get("runId") != null) {
            builder.environment().put("FENGYU_RUN_ID", String.valueOf(envelope.get("runId")));
        }
        if (hook.env() != null) {
            builder.environment().putAll(hook.env());
        }
        if (hook.workingDir() != null && !hook.workingDir().isBlank()) {
            builder.directory(new java.io.File(hook.workingDir()));
        }
        builder.redirectErrorStream(false);
        Process process = builder.start();
        // Feed the envelope on a writer thread: a fast hook (echo-style) may exit before
        // reading stdin, and the resulting broken pipe must not void the hook's verdict —
        // only its stdout/exit code decide the outcome.
        Thread.ofVirtual().start(() -> {
            try (var stdin = process.getOutputStream()) {
                stdin.write(JSON.writeValueAsString(envelope).getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            } catch (Exception pipeRace) {
                // Hook exited without reading stdin — expected for simple hooks.
            }
        });
        // Drain BOTH pipes concurrently while the process runs (P2-4): a hook that
        // writes more than the OS pipe buffer would otherwise block on write, never
        // exit, and be failed open as a timeout — turning a deny into an allow.
        java.io.ByteArrayOutputStream outBuf = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream errBuf = new java.io.ByteArrayOutputStream();
        Thread outDrain = drain(process.getInputStream(), outBuf);
        Thread errDrain = drain(process.getErrorStream(), errBuf);
        boolean finished = process.waitFor(hook.timeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            joinQuietly(outDrain);
            joinQuietly(errDrain);
            log.warn("hook '{}' timed out after {} — failing open", hook.name(), hook.timeout());
            return GateOutcome.ALLOW;
        }
        joinQuietly(outDrain);
        joinQuietly(errDrain);
        String stdout = outBuf.toString(StandardCharsets.UTF_8);
        String stderr = errBuf.toString(StandardCharsets.UTF_8);
        return interpret(hook, stdout, stderr, process.exitValue());
    }

    /**
     * Bounded concurrent reader: keeps caching up to {@code MAX_OUTPUT_CHARS}, then keeps
     * READING (into a scratch buffer) so the child never blocks on a full pipe.
     */
    private static Thread drain(java.io.InputStream stream, java.io.ByteArrayOutputStream buffer) {
        return Thread.ofVirtual().start(() -> {
            byte[] chunk = new byte[4096];
            byte[] scratch = new byte[4096];
            try {
                int read;
                while ((read = stream.read(chunk)) > 0) {
                    synchronized (buffer) {
                        if (buffer.size() < MAX_OUTPUT_CHARS) {
                            buffer.write(chunk, 0, Math.min(read,
                                    MAX_OUTPUT_CHARS - buffer.size()));
                        }
                    }
                    // Swallow the excess so the writer stays unblocked.
                    while (stream.available() > scratch.length) {
                        int swallowed = stream.read(scratch, 0, scratch.length);
                        if (swallowed <= 0) break;
                    }
                }
            } catch (Exception closed) {
                // Process exited; drain ends.
            }
        });
    }

    private static void joinQuietly(Thread drainer) {
        try {
            drainer.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private GateOutcome runHttp(HookDefinition hook, Map<String, Object> envelope) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(hook.url()))
                .timeout(hook.timeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        JSON.writeValueAsString(envelope), StandardCharsets.UTF_8))
                .build();
        // Stream (not ofString) so the body can be capped while it is read: an http hook
        // endpoint answering with an enormous body must not be buffered whole into the
        // host (CQ-05) — the command path has the same cap via its drain threads.
        HttpResponse<java.io.InputStream> response =
                http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (java.io.InputStream body = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("http hook '{}' returned {} — failing open",
                        hook.name(), response.statusCode());
                return GateOutcome.ALLOW;
            }
            return interpret(hook, readCapped(body), null, 0);
        }
    }

    /**
     * Reads at most {@link #MAX_OUTPUT_CHARS} bytes (consistent with the command-output
     * cap) and drains — discarding — whatever exceeds it, so the connection is not
     * stranded on an unread body.
     */
    private static String readCapped(java.io.InputStream body) throws java.io.IOException {
        java.io.ByteArrayOutputStream kept = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = body.read(chunk)) > 0) {
            int room = MAX_OUTPUT_CHARS - kept.size();
            if (room > 0) kept.write(chunk, 0, Math.min(read, room));
            // Bytes past the cap are read (and dropped) so the sender stays unblocked.
        }
        return kept.toString(StandardCharsets.UTF_8);
    }

    /**
     * The exit-code / gate-JSON ladder: a JSON deny wins on any exit code; exit 2 denies
     * with the first stderr line; exit 0 allows; anything else fails open. A JSON allow
     * on exit 2 is ignored (exit 2 wins — stdout is not processed on it).
     */
    private GateOutcome interpret(HookDefinition hook, String stdout, String stderr, int exitCode) {
        String trimmed = stdout == null ? "" : stdout.trim();
        if (!trimmed.isEmpty()) {
            try {
                JsonNode gate = JSON.readTree(trimmed);
                if (gate != null && gate.isObject()
                        && (gate.has("decision") || gate.has("hookSpecificOutput"))) {
                    String decision = gate.path("decision").asText(null);
                    if ("deny".equals(decision)) {
                        String reason = gate.path("reason").asText(null);
                        if (reason == null || reason.isBlank()) reason = firstLine(stderr);
                        return new GateOutcome(true, reason != null ? reason
                                : "denied by hook '" + hook.name() + "'");
                    }
                    if ("allow".equals(decision)) {
                        if (exitCode == DENY_EXIT_CODE) {
                            log.warn("hook '{}' returned decision=allow with exit code 2 — exit code wins (deny)",
                                    hook.name());
                        } else {
                            return GateOutcome.ALLOW;
                        }
                    } else if (decision != null) {
                        // Unknown decision value: failure so typos surface — fail open but loud.
                        log.warn("hook '{}' returned unknown decision '{}' — failing open",
                                hook.name(), decision);
                        return GateOutcome.ALLOW;
                    }
                }
            } catch (Exception notJson) {
                // Non-JSON stdout falls through to the exit-code ladder.
            }
        }
        if (exitCode == 0) return GateOutcome.ALLOW;
        if (exitCode == DENY_EXIT_CODE) {
            String reason = firstLine(stderr);
            return new GateOutcome(true, reason != null ? reason
                    : "denied by hook '" + hook.name() + "' (exit code " + DENY_EXIT_CODE + ")");
        }
        log.warn("hook '{}' failed with exit code {} — failing open", hook.name(), exitCode);
        return GateOutcome.ALLOW;
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) return null;
        String[] lines = text.strip().split("\r?\n");
        return lines[0].isBlank() ? null : lines[0];
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_OUTPUT_CHARS) return value;
        return value.substring(0, MAX_OUTPUT_CHARS) + "…";
    }

    /** Wraps a hook command for the platform shell, mirroring {@code CommandExecuteTool}. */
    static List<String> shellWrap(String command) {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            return List.of("cmd", "/c", command);
        }
        return List.of("/bin/sh", "-c", command);
    }

    /** Events FengYu can emit today; {@link #PRE_TOOL_USE} is the only gate. */
    public enum HookEvent {
        PRE_TOOL_USE("pre_tool_use", true),
        POST_TOOL_USE("post_tool_use", true),
        POST_TOOL_USE_FAILURE("post_tool_use_failure", true),
        RUN_COMPLETE("run_complete", false),
        RUN_ERROR("run_error", false);

        private final String wireName;
        private final boolean matchesTools;

        HookEvent(String wireName, boolean matchesTools) {
            this.wireName = wireName;
            this.matchesTools = matchesTools;
        }

        public String wireName() {
            return wireName;
        }

        boolean matchesTools() {
            return matchesTools;
        }

        public static HookEvent fromWire(String value) {
            if (value == null) return null;
            for (HookEvent event : values()) {
                if (event.wireName.equalsIgnoreCase(value) || event.name().equalsIgnoreCase(value)) {
                    return event;
                }
            }
            // camelCase aliases so grok-shaped hooks files (PreToolUse, …) parse natively.
            return java.util.Map.of(
                    "pretooluse", PRE_TOOL_USE,
                    "posttooluse", POST_TOOL_USE,
                    "posttoolusefailure", POST_TOOL_USE_FAILURE,
                    "runcomplete", RUN_COMPLETE,
                    "runerror", RUN_ERROR)
                    .get(value.toLowerCase(java.util.Locale.ROOT).replaceAll("[_\\-]", ""));
        }
    }

    /**
     * One configured hook. {@code workingDir} and {@code env} are how plugin-delivered
     * hooks bind to their package root ({@code FENGYU_PLUGIN_ROOT}/{@code FENGYU_PLUGIN_DATA})
     * without letting the hook text itself repoint those — plugin-owned keys are added
     * by the loader, not the hook author.
     */
    public record HookDefinition(String name, HookEvent event, String matcher,
                                 Type type, String command, String url,
                                 Duration timeout, boolean enabled,
                                 String workingDir, Map<String, String> env) {

        public enum Type { COMMAND, HTTP }

        public static HookDefinition command(String name, HookEvent event, String matcher,
                                             String command, long timeoutSeconds) {
            return new HookDefinition(name, event, matcher, Type.COMMAND, command, null,
                    Duration.ofSeconds(timeoutSeconds), true, null, Map.of());
        }

        public static HookDefinition http(String name, HookEvent event, String matcher,
                                          String url, long timeoutSeconds) {
            return new HookDefinition(name, event, matcher, Type.HTTP, null, url,
                    Duration.ofSeconds(timeoutSeconds), true, null, Map.of());
        }

        public HookDefinition withRuntime(String dir, Map<String, String> environment) {
            return new HookDefinition(name, event, matcher, type, command, url,
                    timeout, enabled, dir, environment);
        }
    }
}
