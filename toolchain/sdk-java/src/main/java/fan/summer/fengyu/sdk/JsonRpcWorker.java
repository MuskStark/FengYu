package fan.summer.fengyu.sdk;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Small, dependency-light JSON-RPC 2.0 worker runtime for FengYu child processes.
 *
 * <p><b>Parent-death watchdog.</b> The production entry point {@link #run()} installs two
 * complementary watchdogs so a worker can never outlive its host:
 * <ul>
 *   <li><b>stdin EOF (primary).</b> When the host closes the worker's stdin pipe — which the OS
 *       does automatically when the host JVM dies — {@link StdioTransport#readFrame()} returns
 *       {@code null}, {@link #serve(RpcTransport)} returns, and {@code run()}'s finally block
 *       calls {@code System.exit(0)}. This covers graceful host shutdown and most host crashes.</li>
 *   <li><b>parent-process liveness (auxiliary).</b> A daemon thread polls the snapshot of the
 *       parent {@link ProcessHandle}; if the parent disappears while {@code serve()} is still
 *       running (e.g. a pipe kept open by an intermediate launcher), the worker exits. This is a
 *       fallback for the rare cases where stdin does not close promptly.</li>
 * </ul>
 * <p>Both watchdogs converge on {@code System.exit(0)}: a worker that has handed control back to
 * {@code serve()} must still terminate even if the plugin spun up non-daemon threads (a HikariCP
 * pool, a scheduled executor, etc.), which would otherwise keep the JVM alive and hold file locks
 * on embedded databases.
 */
public final class JsonRpcWorker {
    private static final Logger log = LoggerFactory.getLogger(JsonRpcWorker.class);

    /** How often the parent-liveness watchdog polls. Package-private for tests. */
    static final long PARENT_WATCHDOG_INTERVAL_SECONDS = 1;

    private final Gson json = new Gson();
    private final Map<String, PluginHandler> handlers = new ConcurrentHashMap<>();
    /**
     * Invoked when the worker must terminate its JVM. Production wires {@link System#exit(int)};
     * tests inject a no-op recorder so the test JVM is not killed. Package-private and overridable
     * via {@link #withExitHandler(java.util.function.IntConsumer)} for testability.
     */
    private volatile java.util.function.IntConsumer exitHandler = System::exit;

    public JsonRpcWorker on(String method, PluginHandler handler) {
        if (method == null || method.isBlank()) throw new IllegalArgumentException("method is required");
        java.util.Objects.requireNonNull(handler, "handler");
        if (handlers.putIfAbsent(method, handler) != null) throw new IllegalArgumentException("duplicate method: " + method);
        return this;
    }

    public void run() throws Exception {
        run(defaultParentLivenessProbe());
    }

    /**
     * Production entry point with an injectable parent-liveness probe (for tests). Installs the
     * parent-death watchdog, drives {@link #serve(RpcTransport)} against {@code System.in/out}, and
     * guarantees JVM termination in the finally block — see the class javadoc for the full contract.
     *
     * <p>The stdin-EOF path is intrinsic to {@code serve()}: it returns once {@code readFrame()}
     * yields {@code null}. The explicit {@code System.exit(0)} here ensures the JVM actually exits
     * even when a plugin has left non-daemon threads (DB pools, executors) that would otherwise
     * keep it alive and hold embedded-DB file locks.
     *
     * @param parentAlive returns {@code true} while the worker's parent process is still alive;
     *                    returning {@code false} triggers the auxiliary watchdog exit. Production
     *                    passes {@link #defaultParentLivenessProbe()}; tests inject a controllable one.
     */
    void run(BooleanSupplier parentAlive) throws Exception {
        InputStream protocolInput = System.in;
        PrintStream protocolOutput = System.out;
        System.setOut(System.err);
        // Visible on stderr (so via the host's plugin-<id>-stderr drain) — confirms the worker
        // actually started. Without it a worker that exits before reading stdin leaves no trace.
        log.info("Plugin worker started");
        // Auxiliary watchdog: if the parent process vanishes while serve() is still blocked on
        // stdin (pipe not yet closed), exit. The primary path is stdin EOF in serve().
        Thread watcher = startParentWatchdog(parentAlive);
        boolean cleanExit = false;
        try {
            serve(new StdioTransport(protocolInput, protocolOutput));
            cleanExit = true;
        } finally {
            if (watcher != null) watcher.interrupt();
            log.info("Plugin worker shutting down");
            System.setOut(protocolOutput);
            // Only force JVM exit on a clean serve() return (stdin EOF / watchdog). On an exception
            // we re-throw and let the caller/JVM decide, matching the pre-1.2 behaviour and keeping
            // the worker's own diagnostics visible. The exit guarantees a worker with lingering
            // non-daemon threads (HikariCP, executors) still terminates and releases DB file locks.
            if (cleanExit) exitWorker(0);
        }
    }

    /**
     * Daemon thread that exits the worker when the parent process is gone. Returns {@code null} on
     * platforms without a resolvable parent (no parent handle → nothing to watch), so production
     * workers with no parent simply rely on the stdin-EOF primary path.
     */
    private Thread startParentWatchdog(BooleanSupplier parentAlive) {
        if (parentAlive == null) return null;
        AtomicBoolean firstCheck = new AtomicBoolean(true);
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                boolean alive;
                try {
                    alive = parentAlive.getAsBoolean();
                } catch (Throwable dropped) {
                    return; // probe is broken; stdin-EOF path still guards the worker
                }
                // Skip the very first check: a just-snapshotted parent is (briefly) alive, and a
                // stale/unknown pid should not insta-kill the worker before serve() gets going.
                if (!firstCheck.compareAndSet(true, false) && !alive) {
                    log.warn("Plugin worker parent process exited; watchdog shutting down worker");
                    exitWorker(0);
                    return;
                }
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(PARENT_WATCHDOG_INTERVAL_SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "fengyu-worker-parent-watchdog");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * Default parent-liveness probe: snapshots the parent {@link ProcessHandle} at first poll and
     * reports whether it is still alive. Returns a probe that always says {@code true} (disabling
     * the auxiliary watchdog) when the parent cannot be resolved — the stdin-EOF path still covers
     * the worker.
     */
    private static BooleanSupplier defaultParentLivenessProbe() {
        return new BooleanSupplier() {
            private volatile ProcessHandle parent;
            @Override public boolean getAsBoolean() {
                ProcessHandle p = parent;
                if (p == null) {
                    p = ProcessHandle.current().parent().orElse(null);
                    parent = p;
                }
                return p == null || p.isAlive();
            }
        };
    }

    /**
     * Replace the JVM-exit handler. Production leaves {@link System#exit(int)} in place; tests pass
     * a recorder (e.g. an {@link java.util.concurrent.atomic.AtomicInteger}) so the worker can be
     * driven to its exit path without halting the test JVM.
     *
     * @return this worker, for chaining off the constructor
     */
    public JsonRpcWorker withExitHandler(java.util.function.IntConsumer exitHandler) {
        this.exitHandler = java.util.Objects.requireNonNull(exitHandler);
        return this;
    }

    /** Indirection so tests can swap out {@code System.exit} (final, otherwise un-mockable). */
    void exitWorker(int code) {
        exitHandler.accept(code);
    }

    public void run(InputStream input, OutputStream output) throws Exception {
        try (StdioTransport transport = new StdioTransport(input, output)) {
            serve(transport);
        }
    }

    /**
     * Drive the dispatch loop against any {@link RpcTransport}. Reads newline-delimited JSON-RPC
     * 2.0 requests, dispatches each to the registered handler, and writes one response frame per
     * request. Returns cleanly when the transport reaches end-of-stream ({@code readFrame() == null}).
     *
     * <p>This method performs <strong>no</strong> {@code System.setOut} redirection — that behaviour
     * is exclusive to the stdio entry point {@link #run()}. Socket / in-memory transports use this
     * method directly, so handler {@code System.out} writes go wherever the caller has pointed them.
     *
     * @param transport the frame-oriented transport (stdin/stdout, loopback socket, in-memory)
     * @throws Exception if the transport raises a read/write error
     */
    public void serve(RpcTransport transport) throws Exception {
        String line;
        while (transport.isOpen() && (line = transport.readFrame()) != null) {
            Map<String, Object> response = new LinkedHashMap<>(); response.put("jsonrpc", "2.0");
            // Hoisted before the try so the generic catch can log them WITHOUT the raw request frame.
            // The frame carries the full params (passwords, mail bodies, parsed paths); logging it
            // leaks caller secrets to stderr, which the host forwards to its console + log surface.
            String method = "<unknown>";
            Object requestId = null;
            try {
                Map<String, Object> request = parseRequest(line);
                requestId = request.get("id");
                method = (String) request.get("method");
                @SuppressWarnings("unchecked") Map<String, Object> params = request.get("params") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
                if (PluginLogging.SET_LEVEL_METHOD.equals(method)) {
                    PluginLogging.setLevel(string(params, "level"));
                    // This built-in control message is a JSON-RPC notification. It deliberately has
                    // no id and no response, so a settings change never occupies a pending call slot.
                    if (requestId == null) continue;
                    response.put("id", requestId);
                    response.put("result", Map.of("level", PluginLogging.level()));
                    transport.writeFrame(json.toJson(response));
                    continue;
                }
                response.put("id", requestId);
                PluginHandler handler = handlers.get(method);
                if (handler == null) throw new RpcException(-32601, "Unknown method: " + method);
                response.put("result", handler.handle(params));
            } catch (RpcException e) {
                if (e.requestId() != null) response.put("id", e.requestId());
                response.put("error", Map.of("code", e.code(), "message", e.getMessage()));
            } catch (Exception e) {
                // Log only call identity and exception type. Handler exception messages and stack
                // traces may embed caller params (passwords, mail bodies, paths), and stderr is
                // forwarded into the host log surface.
                log.warn("Plugin worker dispatch failed for method={} id={}: {}",
                    method, requestId, e.getClass().getSimpleName());
                // Preserve the handler diagnostic for the direct caller. The host must not copy
                // this untrusted message into shared logs; PluginProcessManager logs only its type.
                response.put("error", Map.of("code", -32000, "message", String.valueOf(e.getMessage())));
            }
            transport.writeFrame(json.toJson(response));
        }
    }

    public static String string(Map<String, Object> params, String key) {
        Object value = params.get(key); return value == null ? null : value.toString();
    }
    public static int integer(Map<String, Object> params, String key, int fallback) {
        Object value = params.get(key); return value instanceof Number number ? number.intValue() : fallback;
    }

    private Map<String, Object> parseRequest(String line) {
        Map<String, Object> request;
        try {
            request = json.fromJson(line, new TypeToken<Map<String, Object>>() {}.getType());
        } catch (com.google.gson.JsonParseException e) {
            throw new RpcException(-32700, "Parse error", null);
        }
        if (request == null || !"2.0".equals(request.get("jsonrpc"))
                || !(request.get("method") instanceof String method) || method.isBlank()) {
            throw new RpcException(-32600, "Invalid Request", request == null ? null : request.get("id"));
        }
        return request;
    }

    public static final class RpcException extends RuntimeException {
        private final int code;
        private final Object requestId;
        public RpcException(int code, String message) { this(code, message, null); }
        public RpcException(int code, String message, Object requestId) {
            super(message); this.code = code; this.requestId = requestId;
        }
        public int code() { return code; }
        public Object requestId() { return requestId; }
    }
}
