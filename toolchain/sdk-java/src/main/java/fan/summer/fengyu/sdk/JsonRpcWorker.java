package fan.summer.fengyu.sdk;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Small, dependency-light JSON-RPC 2.0 worker runtime for FengYu child processes.
 *
 * <h2>Concurrency model (1.4.0)</h2>
 * <p>The dispatch loop is split so cancellation can arrive while a handler runs:
 * <ul>
 *   <li><b>Reader.</b> {@link #serve(RpcTransport)} reads one frame at a time on its own thread.
 *       Each valid request is dispatched onto a handler pool; the reader does NOT block on the
 *       handler, so a {@code $/cancelRequest} notification arriving mid-handler is read and
 *       applied immediately (mark the call's {@link CancellationToken} + interrupt its thread).</li>
 *   <li><b>Handlers.</b> Run concurrently on a cached pool. Each binds a per-call
 *       {@link RpcContext} (callId, locale, cancellation token) to its thread, invokes the
 *       registered handler, and writes exactly one response frame. All writes are serialized on
 *       a write lock so each emitted JSON object is a single complete line.</li>
 *   <li><b>Drain.</b> At end-of-stream the reader shuts the pool down and awaits up to 60s; any
 *       call still pending past the grace window is force-cancelled.</li>
 * </ul>
 * <p>Normal cancellation returns a {@link RpcError.Code#CANCELLED} response — it is never treated
 * as a worker crash. Only a call that ignores both token and thread interruption past the grace
 * window is forcibly reaped.
 *
 * <p><b>Parent-death watchdog.</b> The production entry point {@link #run()} installs two
 * complementary watchdogs so a worker can never outlive its host:
 * <ul>
 *   <li><b>stdin EOF (primary).</b> When the host closes the worker's stdin pipe — which the OS
 *       does automatically when the host JVM dies — {@link StdioTransport#readFrame()} returns
 *       {@code null}, {@link #serve(RpcTransport)} returns, and {@code run()}'s finally block
 *       calls {@code System.exit(0)}.</li>
 *   <li><b>parent-process liveness (auxiliary).</b> A daemon thread polls the snapshot of the
 *       parent {@link ProcessHandle}; if the parent disappears while {@code serve()} is still
 *       running, the worker exits.</li>
 * </ul>
 */
public final class JsonRpcWorker {
    private static final Logger log = LoggerFactory.getLogger(JsonRpcWorker.class);

    /** How often the parent-liveness watchdog polls. Package-private for tests. */
    static final long PARENT_WATCHDOG_INTERVAL_SECONDS = 1;
    /** Grace window for in-flight handlers to drain after EOF before force-cancelling. */
    static final long DRAIN_TIMEOUT_SECONDS = 60;
    /** JSON-RPC notification method the host sends to cancel an in-flight request. */
    static final String CANCEL_METHOD = "$/cancelRequest";

    private final Gson json = new Gson();
    private final Map<String, PluginHandler> handlers = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<AutoCloseable> closeables = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final String pluginId = envOrProperty("FENGYU_PLUGIN_ID");
    private final String pluginRoot = envOrProperty("FENGYU_PLUGIN_ROOT");
    private volatile java.util.function.IntConsumer exitHandler = System::exit;

    // ── registration ────────────────────────────────────────────────────────

    /** Register a low-level handler that receives the raw params map. */
    public JsonRpcWorker on(String method, PluginHandler handler) {
        if (method == null || method.isBlank()) throw new IllegalArgumentException("method is required");
        Objects.requireNonNull(handler, "handler");
        if (handlers.putIfAbsent(method, handler) != null) {
            throw new IllegalArgumentException("duplicate method: " + method);
        }
        return this;
    }

    /**
     * Register a typed handler. The worker deserializes JSON-RPC {@code params} into {@code Input}
     * (via Gson), binds an {@link RpcContext} to the handler thread, and serializes the returned
     * {@code Output} back into the response. {@code outputClass} is accepted for API symmetry /
     * future validation; the returned value is serialized by Gson regardless of its declared type.
     *
     * @param name        the method name (typically a {@code PluginMethods} constant)
     * @param inputClass  the generated input record class
     * @param outputClass the generated output record class (or {@code Object} if the method has no
     *                    declared output schema)
     * @param handler     the typed handler
     */
    public <I, O> JsonRpcWorker method(String name, Class<I> inputClass, Class<O> outputClass, RpcHandler<I, O> handler) {
        Objects.requireNonNull(name, "method name");
        if (name.isBlank()) throw new IllegalArgumentException("method name is required");
        Objects.requireNonNull(inputClass, "inputClass");
        Objects.requireNonNull(outputClass, "outputClass");
        Objects.requireNonNull(handler, "handler");
        PluginHandler adapter = params -> {
            I input = json.fromJson(json.toJson(params), inputClass);
            return handler.handle(input, RpcContext.current());
        };
        return on(name, adapter);
    }

    /** Register a worker-owned resource to close in reverse order before the process exits. */
    public JsonRpcWorker onClose(AutoCloseable resource) {
        Objects.requireNonNull(resource, "resource");
        if (closed.get()) throw new IllegalStateException("worker is already closed");
        closeables.add(resource);
        return this;
    }

    // ── entry points (unchanged shape; serve() is now concurrent) ───────────

    public void run() throws Exception {
        run(defaultParentLivenessProbe());
    }

    void run(BooleanSupplier parentAlive) throws Exception {
        InputStream protocolInput = System.in;
        PrintStream protocolOutput = System.out;
        System.setOut(System.err);
        log.info("Plugin worker started");
        Thread watcher = startParentWatchdog(parentAlive);
        boolean cleanExit = false;
        try {
            serve(new StdioTransport(protocolInput, protocolOutput));
            cleanExit = true;
        } finally {
            if (watcher != null) watcher.interrupt();
            log.info("Plugin worker shutting down");
            closeResources();
            System.setOut(protocolOutput);
            if (cleanExit) exitWorker(0);
        }
    }

    private Thread startParentWatchdog(BooleanSupplier parentAlive) {
        if (parentAlive == null) return null;
        AtomicBoolean firstCheck = new AtomicBoolean(true);
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                boolean alive;
                try {
                    alive = parentAlive.getAsBoolean();
                } catch (Throwable dropped) {
                    return;
                }
                if (!firstCheck.compareAndSet(true, false) && !alive) {
                    log.warn("Plugin worker parent process exited; watchdog shutting down worker");
                    closeResources();
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

    public JsonRpcWorker withExitHandler(java.util.function.IntConsumer exitHandler) {
        this.exitHandler = Objects.requireNonNull(exitHandler);
        return this;
    }

    void exitWorker(int code) {
        exitHandler.accept(code);
    }

    public void run(InputStream input, OutputStream output) throws Exception {
        PrintStream savedOut = System.out;
        PrintStream protocolOutput = output instanceof PrintStream ps
            ? ps : new PrintStream(output, true, StandardCharsets.UTF_8);
        System.setOut(System.err);
        try (StdioTransport transport = new StdioTransport(input, protocolOutput)) {
            serve(transport);
        } finally {
            closeResources();
            System.setOut(savedOut);
        }
    }

    private void closeResources() {
        if (!closed.compareAndSet(false, true)) return;
        for (int i = closeables.size() - 1; i >= 0; i--) {
            try { closeables.get(i).close(); }
            catch (Exception e) {
                log.warn("Plugin worker resource close failed for {}: {}",
                    closeables.get(i).getClass().getSimpleName(), e.getClass().getSimpleName());
            }
        }
        closeables.clear();
    }

    // ── concurrent dispatch loop ────────────────────────────────────────────

    /** Per-call bookkeeping so the reader can cancel a handler running on the pool. */
    private static final class PendingCall {
        final CancellationToken token;
        volatile Thread thread;
        PendingCall(CancellationToken token) { this.token = token; }
        void cancel() {
            token.cancel();
            Thread t = thread;
            if (t != null) t.interrupt();
        }
    }

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    /**
     * Drive the dispatch loop against any {@link RpcTransport}. Reads newline-delimited JSON-RPC
     * 2.0 frames, dispatches each request onto a handler pool (so {@code $/cancelRequest}
     * notifications are still read while handlers run), serializes one response frame per request,
     * and drains all in-flight calls cleanly at end-of-stream.
     */
    public void serve(RpcTransport transport) throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fengyu-worker-handler-" + THREAD_SEQ.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        ConcurrentMap<Object, PendingCall> pending = new ConcurrentHashMap<>();
        Object writeLock = new Object();

        try {
            String line;
            while (transport.isOpen() && (line = transport.readFrame()) != null) {
                Object id = null;
                String method = "<unknown>";
                try {
                    Map<String, Object> request = parseRequest(line);
                    id = request.get("id");
                    method = (String) request.get("method");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> params = request.get("params") instanceof Map<?, ?> map
                        ? (Map<String, Object>) map : Map.of();

                    // Built-in logging-control notification (unchanged behaviour).
                    if (PluginLogging.SET_LEVEL_METHOD.equals(method)) {
                        PluginLogging.setLevel(str(params, "level"));
                        if (id == null) continue;
                        Map<String, Object> resp = envelope(id);
                        resp.put("result", Map.of("level", PluginLogging.level()));
                        writeFrame(transport, writeLock, resp);
                        continue;
                    }

                    // Cancellation notification: no id, no response — just signal the target call.
                    if (CANCEL_METHOD.equals(method)) {
                        PendingCall target = pending.remove(params.get("id"));
                        if (target != null) target.cancel();
                        log.debug("received $/cancelRequest for id={}", params.get("id"));
                        continue;
                    }

                    final CancellationToken token = new CancellationToken();
                    if (id != null) {
                        // A duplicate request id while the first call is still in flight violates
                        // JSON-RPC's id-uniqueness; cancel the older call so its thread frees and
                        // its token reports CANCELLED, then track the new call under the same id.
                        PendingCall created = new PendingCall(token);
                        PendingCall previous = pending.put(id, created);
                        if (previous != null) previous.cancel();
                    }
                    final PluginHandler handler = handlers.get(method);
                    final Object fid = id;
                    final String fmethod = method;
                    final Map<String, Object> fparams = params;
                    pool.submit(() -> dispatchOne(transport, writeLock, fid, fmethod, fparams, handler, token, pending));
                } catch (RpcException e) {
                    Object errId = e.requestId() != null ? e.requestId() : id;
                    Map<String, Object> resp = envelope(errId);
                    resp.put("error", errorEnvelope(e));
                    writeFrame(transport, writeLock, resp);
                }
            }
            // EOF: stop accepting new work, let in-flight handlers finish (cooperative drain).
            pool.shutdown();
            if (!pool.awaitTermination(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("worker drain timed out with {} in-flight call(s); force-cancelling", pending.size());
                for (PendingCall c : pending.values()) c.cancel();
                pool.shutdownNow();
                pool.awaitTermination(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void dispatchOne(RpcTransport transport, Object writeLock, Object id, String method,
            Map<String, Object> params, PluginHandler handler, CancellationToken token,
            ConcurrentMap<Object, PendingCall> pending) {
        Map<String, Object> resp = envelope(id);
        // Register this call's thread so a $/cancelRequest that lands mid-handler can interrupt it.
        if (id != null) {
            PendingCall call = pending.get(id);
            if (call != null) call.thread = Thread.currentThread();
        }
        RpcContext.bind(new RpcContext(id == null ? null : id.toString(), pluginId, pluginRoot,
                str(params, "locale"), token, log));
        try {
            token.throwIfCancelled();
            if (handler == null) {
                throw new RpcException(-32601, "Unknown method: " + method);
            }
            Object result = handler.handle(params);
            if (token.isCancelled()) {
                // Handler returned normally despite cancellation — honour the cancel signal.
                throw new RpcException(RpcError.Code.CANCELLED, "request cancelled");
            }
            resp.put("result", result);
        } catch (RpcException e) {
            resp.put("error", errorEnvelope(e));
        } catch (Throwable t) {
            // An unhandled handler Exception/Error is an unexpected bug. Its raw message is
            // untrusted — it may embed caller secrets (credentials, business data, file paths) —
            // so it never enters the response: the caller gets a generic message plus the stable
            // INTERNAL code + data.code label. The full causal chain and stack frames (WHERE it
            // failed) go to this worker's stderr for operator diagnostics; the exception message
            // itself is redacted from stderr too (safeStackTrace). A handler that wants to surface
            // a controlled diagnostic throws RpcException, whose message DOES reach the caller via
            // errorEnvelope above.
            log.warn("Plugin worker dispatch failed for method={} id={}: {}\n{}",
                method, id, t.getClass().getName(), safeStackTrace(t));
            resp.put("error", Map.of(
                "code", RpcError.Code.INTERNAL.jsonRpcCode(),
                "message", "Internal error",
                "data", Map.of("code", RpcError.Code.INTERNAL.name())));
        } finally {
            RpcContext.clear();
            WorkerLocale.clear();
            if (id != null) pending.remove(id);
            try {
                writeFrame(transport, writeLock, resp);
            } catch (Exception e) {
                log.warn("worker failed to write response for method={} id={}: {}",
                    method, id, e.getClass().getSimpleName());
            }
        }
    }

    private Map<String, Object> envelope(Object id) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jsonrpc", "2.0");
        r.put("id", id);
        return r;
    }

    private Map<String, Object> errorEnvelope(RpcException e) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", e.code());
        err.put("message", e.getMessage() == null ? "" : e.getMessage());
        if (e.semanticCode() != null) {
            err.put("data", Map.of("code", e.semanticCode().name()));
        }
        return err;
    }

    /**
     * Format a throwable's causal chain and stack frames WITHOUT the exception messages. Plain
     * (non-RpcException) throwables carry untrusted messages that may embed caller secrets, so the
     * message is redacted from stderr; only the class names, the causal chain, and the stack frames
     * (WHERE it failed) are retained for operator diagnostics.
     */
    private static String safeStackTrace(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        while (cur != null) {
            if (cur != t) sb.append("Caused by: ");
            sb.append(cur.getClass().getName());
            for (StackTraceElement frame : cur.getStackTrace()) sb.append("\n\tat ").append(frame);
            cur = cur.getCause();
            if (cur != null) sb.append('\n');
        }
        return sb.toString();
    }

    private void writeFrame(RpcTransport transport, Object writeLock, Map<String, Object> resp) throws Exception {
        String frame = json.toJson(resp);
        synchronized (writeLock) {
            transport.writeFrame(frame);
        }
    }

    /** Read a string-valued param, coercing to {@code null} when absent (internal helper). */
    private static String str(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null ? null : value.toString();
    }

    /**
     * Resolve a worker environment value. The production host injects {@code FENGYU_PLUGIN_ID} /
     * {@code FENGYU_PLUGIN_ROOT} as process environment variables (read via {@link System#getenv}).
     * The IDE devkit launches the worker in the SAME JVM via {@link System#setProperty}, where
     * {@code getenv} (captured at JVM startup) stays empty — so fall back to the system property to
     * keep {@link RpcContext#pluginId()} / {@link RpcContext#pluginRoot()} populated for in-IDE
     * debugging. Env wins because the host's ProcessBuilder values are authoritative in production.
     */
    private static String envOrProperty(String name) {
        String env = System.getenv(name);
        return (env != null && !env.isEmpty()) ? env : System.getProperty(name);
    }

    @SuppressWarnings("unchecked")
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
}
