package fan.summer.fengyu.plugin.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.ai.service.AiConfigServiceHeadless;
import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.security.ProcessSandbox;
import fan.summer.fengyu.ai.tools.AiPermissionContext;
import fan.summer.fengyu.ai.tools.AiPermissionMode;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Owns isolated plugin backend processes and their newline-delimited JSON-RPC channel.
 *
 * <p><b>Per-call timeout.</b> Every invoke is bounded by a timeout (default {@link
 * #DEFAULT_TIMEOUT_SECONDS}, declarable up to {@link #MAX_TIMEOUT_SECONDS}). When the timeout
 * elapses the worker process is killed — a worker is single-threaded on the SDK side, so a
 * stuck handler cannot be cancelled any other way. On the next call the lazily-restarted
 * worker takes its place. Timeouts therefore behave like crashes: this is deliberate.
 *
 * <p><b>Pipelined concurrency.</b> The per-Worker {@code synchronized} lock was removed: writes
 * to stdin are now guarded by a tiny write lock, and a single resident reader virtual-thread
 * demultiplexes responses by JSON-RPC {@code id} into per-request {@link CompletableFuture}s.
 * Multiple concurrent callers into the same plugin no longer serialize on each other's full
 * round-trip — they only contend on the worker's own single-threaded dispatch (a property of
 * {@code JsonRpcWorker} on the SDK side). This pipelining is what makes the declared timeout
 * meaningful: a slow request no longer blocks unrelated requests.
 */
@Service
public class PluginProcessManager {
    private static final Logger log = LoggerFactory.getLogger(PluginProcessManager.class);
    /** Default per-call timeout when neither the caller nor the manifest declares one. */
    public static final long DEFAULT_TIMEOUT_SECONDS = 60;
    /** Hard cap on any declared timeout; prevents a malicious manifest from pinning a worker. */
    public static final long MAX_TIMEOUT_SECONDS = 600;

    private final PluginPackageService packages;
    private final PluginFileGrantService files;
    private final PluginRuntimeEnvironmentService runtimeEnvironment;
    private final PluginLogStore logStore;
    private final ProcessSandbox sandbox;
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private final Map<String, Worker> workers = new ConcurrentHashMap<>();

    public PluginProcessManager(PluginPackageService packages, PluginFileGrantService files,
            PluginRuntimeEnvironmentService runtimeEnvironment, PluginLogStore logStore) {
        this(packages, files, runtimeEnvironment, logStore,
                new ProcessSandbox());
    }

    @Autowired
    public PluginProcessManager(PluginPackageService packages, PluginFileGrantService files,
            PluginRuntimeEnvironmentService runtimeEnvironment, PluginLogStore logStore,
            ProcessSandbox sandbox) {
        this.packages = packages;
        this.files = files;
        this.runtimeEnvironment = runtimeEnvironment;
        this.logStore = logStore;
        this.sandbox = sandbox;
    }

    /** Invoke with the plugin-wide default timeout (manifest {@code backend.callTimeoutSeconds} or 60s). */
    public Object invoke(String pluginId, String method, Map<String, Object> params) {
        return invoke(pluginId, method, params, -1);
    }

    /**
     * Invoke with an explicit per-call timeout in seconds. Caller-supplied values are clamped to
     * {@code [1, MAX_TIMEOUT_SECONDS]}; {@code -1} means "use the plugin-wide default".
     */
    public Object invoke(String pluginId, String method, Map<String, Object> params, long timeoutSeconds) {
        if (!packages.isEnabled(pluginId)) throw new IllegalArgumentException("Plugin is disabled: " + pluginId);
        PluginManifest manifest = packages.find(pluginId)
            .orElseThrow(() -> new IllegalArgumentException("Plugin is not installed: " + pluginId));
        if (manifest.backend() == null || manifest.backend().command() == null || manifest.backend().command().isBlank()) {
            throw new IllegalArgumentException("Plugin has no backend: " + pluginId);
        }
        if (!"json-rpc-2.0".equals(manifest.backend().protocol())) {
            throw new IllegalArgumentException("Unsupported plugin backend protocol");
        }
        long timeout = resolveTimeout(timeoutSeconds, manifest);
        long grantVersion = files.grantVersion(pluginId);
        boolean fullAccess = AiPermissionContext.current() == AiPermissionMode.FULL_ACCESS
                || AiConfigServiceHeadless.isUnsandboxedPluginsEnabled();
        Worker worker = workers.compute(pluginId, (id, current) -> {
            if (current != null && current.alive() && current.grantVersion() == grantVersion
                    && current.fullAccess() == fullAccess) return current;
            if (current != null) current.close();
            return start(id, manifest, fullAccess);
        });
        // Log only the param KEYS, never the values. A caller can pass arbitrary credentials or
        // body text in params (e.g. an SMTP password for email_account_save); logging the value —
        // even truncated — leaks it to the console, the host log file, and the plugin log surface.
        // Keys describe the call shape without revealing anything sensitive.
        String keys = paramKeys(params);
        log.info("Plugin {} invoke -> {}{}", pluginId, method, keys);
        logStore.append(pluginId, "INFO", "invoke " + method + keys);
        long startedNanos = System.nanoTime();
        try {
            @SuppressWarnings("unchecked") Map<String, Object> resolved = (Map<String, Object>) resolveRefs(pluginId, params == null ? Map.of() : params);
            // Resolved params carry FileRefs turned into absolute paths (and still hold any secret
            // values), so only their KEYS are safe to log even at DEBUG.
            log.debug("Plugin {} resolved {} keys={}", pluginId, method, resolved.keySet());
            // Worker.invoke enforces its own timeout via future.get(timeout); on timeout it throws
            // IllegalStateException, which the catch below turns into a worker kill + restart.
            Object result = worker.invoke(method, resolved, timeout);
            long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000;
            log.info("Plugin {} <- {} ok ({} ms)", pluginId, method, elapsedMs);
            logStore.append(pluginId, "INFO", method + " ok (" + elapsedMs + " ms)");
            return result;
        } catch (RuntimeException e) {
            long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000;
            // Worker error messages are untrusted and may echo request values such as passwords,
            // mail bodies, or filesystem paths. Preserve the exception for the direct API caller,
            // but keep shared console/file/SSE logs limited to the failure type.
            String failureType = e.getClass().getSimpleName();
            log.warn("Plugin {} <- {} failed after {} ms ({})", pluginId, method, elapsedMs, failureType);
            logStore.append(pluginId, "WARN", method + " failed (" + failureType + ")");
            // Only unrecoverable worker state (EOF / IO / timeout / interrupted) tears down the
            // worker. Business errors (IllegalArgumentException, e.g. "plugin is disabled") leave
            // the worker intact for the next call. failAll() drains every pending caller so the
            // stuck handler's siblings learn about the failure instead of hanging.
            if (e instanceof IllegalStateException) {
                worker.failAll("Plugin worker tearing down: " + e.getMessage());
                workers.remove(pluginId, worker);
                worker.close();
            }
            throw e;
        }
    }

    private static long resolveTimeout(long requested, PluginManifest manifest) {
        Long declared = manifest.backend() != null ? manifest.backend().callTimeoutSeconds() : null;
        long effective = requested == -1 ? (declared != null ? declared : DEFAULT_TIMEOUT_SECONDS) : requested;
        if (effective < 1) effective = 1;
        if (effective > MAX_TIMEOUT_SECONDS) effective = MAX_TIMEOUT_SECONDS;
        return effective;
    }

    private Object resolveRefs(String pluginId, Object value) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("id") instanceof String id && id.startsWith("ref_") && map.get("kind") != null) {
                return files.resolve(pluginId, id).toString();
            }
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            map.forEach((key, item) -> out.put(String.valueOf(key), resolveRefs(pluginId, item)));
            return out;
        }
        if (value instanceof List<?> list) return list.stream().map(item -> resolveRefs(pluginId, item)).toList();
        return value;
    }

    public void stop(String pluginId) {
        Worker worker = workers.remove(pluginId);
        if (worker != null) worker.close();
    }

    private Worker start(String id, PluginManifest manifest, boolean fullAccess) {
        Path root = packages.directory(id);
        List<String> command = parseCommand(manifest.backend().command(), root);
        Map<String, String> environment = runtimeEnvironment.environmentFor(manifest);
        SensitiveValueRedactor redactor = SensitiveValueRedactor.fromEnvironment(environment);
        try {
            List<String> permissions = manifest.permissions() == null ? List.of() : manifest.permissions();
            boolean broadFileWrite = false;
            boolean allowNetwork = permissions.contains("network")
                    || permissions.contains("network.email")
                    || permissions.contains("database");
            List<Path> writableRoots = new ArrayList<>();
            writableRoots.add(root);
            String pluginData = environment.get(PluginWorkerProtocol.PLUGIN_DATA_DIR_ENV);
            Path workerTemp = null;
            if (pluginData != null && !pluginData.isBlank()) {
                Path dataDirectory = Path.of(pluginData);
                writableRoots.add(dataDirectory);
                workerTemp = Files.createDirectories(dataDirectory.resolve("tmp"));
                command = withJavaTempDirectory(command, workerTemp);
            }
            writableRoots.addAll(files.writablePaths(id));
            ProcessSandbox.Launch launch = fullAccess
                    ? sandbox.unrestricted(command)
                    : sandbox.plugin(command, root, writableRoots, broadFileWrite, allowNetwork);
            ProcessBuilder builder = new ProcessBuilder(launch.command()).directory(root.toFile());
            builder.environment().put("FENGYU_PLUGIN_ID", id);
            builder.environment().put("FENGYU_PLUGIN_ROOT", root.toString());
            if (workerTemp != null) {
                builder.environment().put("TMPDIR", workerTemp.toString());
                builder.environment().put("TMP", workerTemp.toString());
                builder.environment().put("TEMP", workerTemp.toString());
            }
            environment.forEach(builder.environment()::put);
            Process process = builder.start();
            Thread.ofVirtual().name("plugin-" + id + "-stderr").start(() -> {
                try (BufferedReader errors = process.errorReader(StandardCharsets.UTF_8)) {
                    for (String line; (line = errors.readLine()) != null;) {
                        // Parse before redaction: structured JSON escapes quotes/backslashes, so
                        // replacing a raw secret in the encoded frame can miss it. Redact the
                        // decoded fields instead; legacy free-form stderr follows the same path.
                        PluginLogLineParser.Parsed parsed = PluginLogLineParser.parse(line);
                        PluginLogLineParser.Parsed event = new PluginLogLineParser.Parsed(
                            parsed.level(),
                            redactor.redact(parsed.logger()),
                            redactor.redact(parsed.thread()),
                            redactor.redact(parsed.message()));
                        String message = abbreviateLog(event.message());
                        forwardPluginLog(id, event, message);
                        logStore.append(id, event.level(), event.logger(), event.thread(), message);
                    }
                } catch (IOException ignored) {}
            });
            Worker worker = new Worker(id, process, json, redactor, logStore,
                    files.grantVersion(id), fullAccess);
            worker.startReader();
            // Host lifecycle events use the same effective threshold as forwarded Worker events.
            log.info("Plugin {} worker started (pid={})", id, process.pid());
            logStore.append(id, "INFO", "Worker started (pid=" + process.pid() + ")");
            String isolation = "sandbox=" + launch.backend().id()
                    + ", network=" + (fullAccess || allowNetwork ? "allowed" : "isolated")
                    + ", broadFileWrite=" + broadFileWrite
                    + (AiConfigServiceHeadless.isUnsandboxedPluginsEnabled() ? ", unsandboxedOverride=true" : "");
            log.info("Plugin {} worker isolation: {}", id, isolation);
            logStore.append(id, launch.sandboxed() ? "INFO" : "WARN", isolation);
            return worker;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start plugin backend: " + redactor.redact(e.getMessage()), e);
        }
    }

    private static List<String> parseCommand(String raw, Path root) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (Character.isWhitespace(c) && !quoted) {
                if (!current.isEmpty()) { parts.add(current.toString()); current.setLength(0); }
            } else current.append(c);
        }
        if (quoted) throw new IllegalArgumentException("Unclosed quote in backend command");
        if (!current.isEmpty()) parts.add(current.toString());
        if (parts.isEmpty()) throw new IllegalArgumentException("Backend command is empty");
        if ("java".equals(parts.getFirst()) || "${java}".equals(parts.getFirst())) {
            parts.set(0, Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString());
        }
        for (int i = 0; i < parts.size(); i++) {
            String value = parts.get(i).replace("${pluginRoot}", root.toString());
            if (i > 0 && !Path.of(value).isAbsolute() && Files.exists(root.resolve(value))) {
                value = root.resolve(value).normalize().toString();
            }
            parts.set(i, value);
        }
        return parts;
    }

    private static List<String> withJavaTempDirectory(List<String> command, Path tempDirectory) {
        if (command.isEmpty()) return command;
        String executable = Path.of(command.getFirst()).getFileName().toString();
        if (!List.of("java", "java.exe").contains(executable.toLowerCase(java.util.Locale.ROOT))) {
            return command;
        }
        List<String> configured = new ArrayList<>(command);
        configured.add(1, "-Djava.io.tmpdir=" + tempDirectory.toAbsolutePath().normalize());
        return configured;
    }

    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }

    private static String abbreviate(String value) {
        if (value == null || value.length() <= 240) return value;
        return value.substring(0, 237) + "...";
    }

    private static String abbreviateLog(String value) {
        if (value == null || value.length() <= 16_384) return value;
        return value.substring(0, 16_381) + "...";
    }

    private static void forwardPluginLog(String pluginId, PluginLogLineParser.Parsed event,
            String message) {
        String source = event.logger() == null || event.logger().isBlank()
            ? "stderr" : safeLoggerName(event.logger());
        Logger pluginLogger = LoggerFactory.getLogger("plugin." + safeLoggerName(pluginId) + "." + source);
        String rendered = event.thread() == null || event.thread().isBlank()
            ? message : "[" + event.thread() + "] " + message;
        switch (event.level()) {
            case "TRACE" -> pluginLogger.trace(rendered);
            case "DEBUG" -> pluginLogger.debug(rendered);
            case "WARN" -> pluginLogger.warn(rendered);
            case "ERROR" -> pluginLogger.error(rendered);
            default -> pluginLogger.info(rendered);
        }
    }

    private static String safeLoggerName(String value) {
        if (value == null || value.isBlank()) return "worker";
        String safe = value.replaceAll("[^A-Za-z0-9_$.-]", "_");
        return safe.length() <= 160 ? safe : safe.substring(0, 160);
    }

    /**
     * One-line, leak-safe summary of the raw invoke params for operation logs: lists the param
     * KEYS only, never the values. A value (a password, mail body, parsed absolute path) can be a
     * secret, and truncating it is not a safe redaction — so it is never stringified here. Returns
     * an empty string for {@code null}/empty params so the log line stays clean.
     */
    private static String paramKeys(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return "";
        return " keys=" + params.keySet();
    }

    @PreDestroy public void close() { workers.values().forEach(Worker::close); workers.clear(); }

    /** Push a log-level change to every running SDK Worker without restarting it. */
    public void updateLogLevel(String level) {
        workers.values().stream().filter(Worker::alive).forEach(worker ->
            worker.sendNotification(PluginWorkerProtocol.SET_LOG_LEVEL_METHOD, Map.of("level", level)));
    }

    /**
     * One Worker per plugin process. Concurrency model:
     * <ul>
     *   <li>writer lock — serialises stdin writes only (sub-millisecond critical section).</li>
     *   <li>reader virtual-thread — resident for the worker's lifetime, demultiplexes stdout
     *       lines by JSON-RPC {@code id} into {@link #pending} futures.</li>
     *   <li>{@link #failAll(String)} — drains {@link #pending} on EOF / IO error / close, so every
     *       blocked caller learns about the failure rather than hanging until its own timeout.</li>
     * </ul>
     */
    static final class Worker {
        private final String pluginId;
        private final Process process;
        private final ObjectMapper json;
        private final SensitiveValueRedactor redactor;
        private final PluginLogStore logStore;
        private final BufferedWriter writer;
        private final BufferedReader reader;
        private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
        private volatile boolean closed = false;
        private final long grantVersion;
        private final boolean fullAccess;

        Worker(String pluginId, Process process, ObjectMapper json, SensitiveValueRedactor redactor,
                PluginLogStore logStore, long grantVersion, boolean fullAccess) {
            this.pluginId = pluginId;
            this.process = process;
            this.json = json;
            this.redactor = redactor;
            this.logStore = logStore;
            this.grantVersion = grantVersion;
            this.fullAccess = fullAccess;
            this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        }

        long grantVersion() { return grantVersion; }
        boolean fullAccess() { return fullAccess; }

        /** Start the resident reader thread that routes stdout lines by JSON-RPC id. */
        void startReader() {
            Thread.ofVirtual().name("plugin-" + pluginId + "-stdout").start(() -> {
                try {
                    for (String line; (line = reader.readLine()) != null;) {
                        JsonNode response;
                        try {
                            response = json.readTree(line);
                        } catch (IOException invalidJson) {
                            String safe = abbreviate(redactor.redact(line));
                            log.warn("Plugin {} emitted non-JSON stdout: {}", pluginId, safe);
                            // A non-JSON line on the protocol pipe usually means the worker wrote a
                            // log/print to stdout instead of stderr — surface it so it's diagnosable.
                            logStore.append(pluginId, "WARN", "non-JSON stdout: " + safe);
                            continue;
                        }
                        String responseId = response.path("id").asText("");
                        CompletableFuture<JsonNode> slot = responseId.isEmpty() ? null : pending.get(responseId);
                        if (slot == null) {
                            String idText = redactor.redact(response.path("id").asText("<missing>"));
                            log.warn("Plugin {} returned response for unexpected id={}", pluginId, idText);
                            logStore.append(pluginId, "WARN", "unexpected response id=" + idText);
                            continue;
                        }
                        if (response.hasNonNull("error")) {
                            String message = redactor.redact(
                                response.path("error").path("message").asText("Plugin call failed"));
                            slot.completeExceptionally(new IllegalArgumentException(message));
                        } else {
                            slot.complete(response.get("result"));
                        }
                    }
                    failAll("Plugin backend stopped unexpectedly: " + pluginId);
                } catch (IOException e) {
                    failAll("Plugin RPC failed: " + redactor.redact(e.getMessage()));
                }
            });
        }

        /** Invoke a method, returning the raw result node. Blocks up to {@code timeoutSeconds}. */
        Object invoke(String method, Map<String, Object> params, long timeoutSeconds) {
            String id = UUID.randomUUID().toString();
            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            pending.put(id, future);
            try {
                String frame = json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
                // DEBUG-only wire trace: log the id + method but NOT the frame. The frame carries the
                // full params JSON (caller-supplied passwords, mail bodies, parsed paths); the env
                // redactor only knows env-borne secrets, so a param value would leak verbatim here.
                log.debug("Plugin {} \u2192 {} id={}", pluginId, method, id);
                // Writer lock: keep concurrent callers from interleaving frames on stdin.
                synchronized (this) {
                    writer.write(frame);
                    writer.newLine();
                    writer.flush();
                }
                JsonNode result = future.get(timeoutSeconds, TimeUnit.SECONDS);
                log.debug("Plugin {} \u2190 {} id={} ok", pluginId, method, id);
                return json.treeToValue(result, Object.class);
            } catch (java.util.concurrent.TimeoutException e) {
                pending.remove(id, future);
                // Convert the blocking-timeout into the IllegalStateException the caller already
                // treats as "tear down this worker". The outer invoke() will then kill+restart.
                throw new IllegalStateException("Plugin call timed out after " + timeoutSeconds + " seconds: " + pluginId);
            } catch (InterruptedException e) {
                pending.remove(id, future);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Plugin call was interrupted", e);
            } catch (ExecutionException e) {
                pending.remove(id, future);
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Plugin call failed", cause);
            } catch (IOException e) {
                pending.remove(id, future);
                throw new IllegalStateException("Plugin RPC failed: " + redactor.redact(e.getMessage()), e);
            }
        }

        void sendNotification(String method, Map<String, Object> params) {
            if (!alive()) return;
            try {
                String frame = json.writeValueAsString(
                    Map.of("jsonrpc", "2.0", "method", method, "params", params));
                synchronized (this) {
                    writer.write(frame);
                    writer.newLine();
                    writer.flush();
                }
            } catch (IOException e) {
                log.warn("Plugin {} control notification failed: {}", pluginId,
                    redactor.redact(e.getMessage()));
            }
        }

        /** Drain every pending caller with {@code reason}; idempotent. */
        void failAll(String reason) {
            if (closed) return;
            closed = true;
            String snapshot = redactor.redact(reason);
            logStore.append(pluginId, "WARN", "Worker stopped: " + snapshot);
            pending.values().forEach(f -> f.completeExceptionally(new IllegalStateException(snapshot)));
            pending.clear();
        }

        boolean alive() { return process.isAlive() && !closed; }

        void close() {
            failAll("Plugin worker closed: " + pluginId);
            // Destroy the worker JVM itself (graceful SIGTERM, then SIGKILL after 2s). On macOS the
            // sandbox-exec wrapper execve's into java, so process.destroy() hits the worker JVM
            // directly (verified). On Linux bwrap --die-with-parent already reaps the worker when
            // the host dies, but this path still covers an explicit PluginProcessManager.stop().
            process.destroy();
            try { if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
            // Backstop: a worker may spawn grandchildren (e.g. offlinepython's pip subprocess) that
            // are NOT reaped when the worker JVM dies. Walk the worker's descendant tree and force-
            // kill any survivors so they cannot leak (a leaked grandchild can hold file handles or
            // child DB locks of its own). Idempotent: already-dead descendants are skipped.
            killDescendants(process.descendants());
        }

        /** Recursively destroy a process tree, leaves-first to avoid orphaning. */
        private static void killDescendants(java.util.stream.Stream<ProcessHandle> descendants) {
            descendants.forEach(child -> {
                killDescendants(child.children());
                if (child.isAlive()) child.destroyForcibly();
            });
        }
    }
}
