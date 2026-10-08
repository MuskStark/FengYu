package fan.summer.fengyu.sdk;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.reflect.TypeToken;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
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
    /** Reserved startup negotiation method implemented by the SDK, never by plugin code. */
    public static final String INITIALIZE_METHOD = "$/fengyu/initialize";
    public static final int PROTOCOL_VERSION = 4;

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
                JsonElement id = null;
                String method = "<unknown>";
                try {
                    ParsedRequest request = parseRequest(line);
                    id = request.id();
                    method = request.method();
                    Map<String, Object> params = request.params();
                    // The request locale rides in the reserved top-level `_fengyu` envelope (so it
                    // never collides with a plugin method's own `locale` input field). Fall back to
                    // the legacy `params.locale` key for backward compat with an older host that has
                    // not yet adopted the reserved channel.
                    final String requestLocale = localeOf(request.raw(), params);

                    if (INITIALIZE_METHOD.equals(method)) {
                        Number requested = params.get("protocolVersion") instanceof Number number
                            ? number : null;
                        if (requested == null || requested.intValue() != PROTOCOL_VERSION) {
                            throw new RpcException(-32602,
                                "Unsupported FengYu worker protocol: " + requested);
                        }
                        if (id != null) {
                            Map<String, Object> resp = envelope(id);
                            String sdkVersion = JsonRpcWorker.class.getPackage().getImplementationVersion();
                            resp.put("result", Map.of(
                                "protocolVersion", PROTOCOL_VERSION,
                                "runtime", "java",
                                "sdkVersion", sdkVersion == null ? "development" : sdkVersion,
                                "capabilities", List.of("cancellation", "locale", "structuredLogs")
                            ));
                            writeFrame(transport, writeLock, resp);
                        }
                        continue;
                    }

                    // Built-in logging-control notification (unchanged behaviour).
                    if (PluginLogging.SET_LEVEL_METHOD.equals(method)) {
                        try {
                            PluginLogging.setLevel(str(params, "level"));
                        } catch (RuntimeException invalidLevel) {
                            // An invalid level must degrade to an invalid-params error, not unwind
                            // serve() and kill the worker.
                            throw new RpcException(RpcError.Code.INVALID_ARGUMENT,
                                "Invalid log level");
                        }
                        if (id == null) continue;
                        Map<String, Object> resp = envelope(id);
                        resp.put("result", Map.of("level", PluginLogging.level()));
                        writeFrame(transport, writeLock, resp);
                        continue;
                    }

                    // Cancellation notification: no id, no response — just signal the target call.
                    if (CANCEL_METHOD.equals(method)) {
                        PendingCall target = pending.remove(idElementOf(params.get("id")));
                        if (target != null) target.cancel();
                        log.debug("received $/cancelRequest for id={}", params.get("id"));
                        continue;
                    }

                    final CancellationToken token = new CancellationToken();
                    final PendingCall created;
                    if (id != null) {
                        // A duplicate request id while the first call is still in flight violates
                        // JSON-RPC's id-uniqueness; cancel the older call so its thread frees and
                        // its token reports CANCELLED, then track the new call under the same id.
                        created = new PendingCall(token);
                        PendingCall previous = pending.put(id, created);
                        if (previous != null) previous.cancel();
                    } else {
                        created = null;
                    }
                    final PluginHandler handler = handlers.get(method);
                    final JsonElement fid = id;
                    final String fmethod = method;
                    final Map<String, Object> fparams = params;
                    pool.submit(() -> dispatchOne(transport, writeLock, fid, fmethod, fparams, requestLocale, handler, token, created, pending));
                } catch (RpcException e) {
                    Object errId = e.requestId() != null ? e.requestId() : id;
                    Map<String, Object> resp = envelope(errId);
                    resp.put("error", errorEnvelope(e));
                    writeFrame(transport, writeLock, resp);
                } catch (RuntimeException unexpected) {
                    // Anything else a peer-authored frame can provoke out of the reader (a
                    // reserved-method input we failed to pre-validate, a Gson edge case) is an
                    // invalid frame, not a worker crash: answer invalid-params and keep serving.
                    log.warn("worker failed to process frame for method={} id={}: {}\n{}",
                        method, id, unexpected.getClass().getName(), safeStackTrace(unexpected));
                    if (id != null) {
                        Map<String, Object> resp = envelope(id);
                        resp.put("error", errorEnvelope(
                            new RpcException(RpcError.Code.INVALID_ARGUMENT, "Invalid params")));
                        writeFrame(transport, writeLock, resp);
                    }
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
            Map<String, Object> params, String requestLocale, PluginHandler handler,
            CancellationToken token, PendingCall call, ConcurrentMap<Object, PendingCall> pending) {
        Map<String, Object> resp = envelope(id);
        // Register this call's own thread so a $/cancelRequest that lands mid-handler can interrupt
        // it. We use the specific PendingCall captured at dispatch time (not pending.get(id)) so a
        // duplicate-id call that replaced this slot cannot steal or clobber our interrupt target.
        if (call != null) {
            call.thread = Thread.currentThread();
        }
        RpcContext.bind(new RpcContext(id == null ? null : idText(id), pluginId, pluginRoot,
                requestLocale, token, log));
        // Bind WorkerLocale too so message-bundle resolution (PluginMessages / PluginHandlerSupport.t)
        // honours the request locale on this handler thread. Previously only Jobs propagated it, so
        // synchronous handlers resolved messages in English regardless of the request locale. Cleared
        // in the finally below; the InheritableThreadLocal means any Jobs thread spawned from here
        // inherits the correct locale until that clear runs.
        WorkerLocale.set(requestLocale);
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
            // Remove only OUR entry: a duplicate-id call may have already replaced this slot, and
            // removing by key alone would drop the newer call's PendingCall (breaking its
            // cancellation). remove(key, value) is a no-op when the slot no longer maps to `call`.
            if (call != null) pending.remove(id, call);
            // JSON-RPC 2.0: the Server MUST NOT reply to a Notification. Notification handlers
            // still RUN (fire-and-forget, observable via their side effects) but never emit a frame.
            if (id != null) {
                try {
                    writeFrame(transport, writeLock, resp);
                } catch (Exception e) {
                    log.warn("worker failed to write response for method={} id={}: {}",
                        method, id, e.getClass().getSimpleName());
                    // The peer would otherwise wait out its whole call timeout with no frame at all
                    // (e.g. a result that serializes past the 16 MiB frame cap). Answer with a
                    // compact INTERNAL error — always far under the cap; a genuinely dead pipe
                    // fails this write too and is dropped after the debug line.
                    Map<String, Object> fallback = envelope(id);
                    fallback.put("error", Map.of(
                        "code", RpcError.Code.INTERNAL.jsonRpcCode(),
                        "message", "Internal error",
                        "data", Map.of("code", RpcError.Code.INTERNAL.name())));
                    try {
                        writeFrame(transport, writeLock, fallback);
                    } catch (Exception deadPipe) {
                        log.debug("worker could not deliver fallback error frame for method={} id={}",
                            method, id);
                    }
                }
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
     *
     * <p>Package-private: {@link PluginHandlerSupport} and {@link Jobs} log through the same
     * redaction so every SDK call site renders throwables consistently.
     */
    static String safeStackTrace(Throwable t) {
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
     * Resolve the request locale from its reserved channel: the top-level {@code _fengyu.locale}
     * envelope preferred (host-owned, never collides with a plugin method's own {@code locale}
     * input field), falling back to the legacy {@code params.locale} key for a host that has not
     * yet adopted the reserved channel. Returns {@code null} when neither is present.
     */
    private static String localeOf(JsonObject raw, Map<String, Object> params) {
        if (raw.get("_fengyu") instanceof JsonObject fengyu
                && fengyu.get("locale") instanceof JsonPrimitive locale) {
            return locale.getAsString();
        }
        return str(params, "locale");
    }

    /**
     * Normalize a legacy params-carried id (Gson's {@code Map} view surfaces numbers as
     * {@code Double}) to the {@code JsonElement} key form used by the pending-call map, so
     * {@code $/cancelRequest} correlation matches raw request ids. {@code JsonPrimitive} compares
     * numbers by value ({@code 42} and {@code 42.0} are equal), mirroring the old Double-keyed
     * behaviour while ids stay raw for the response echo.
     */
    private static JsonElement idElementOf(Object legacyId) {
        if (legacyId instanceof String s) return new JsonPrimitive(s);
        if (legacyId instanceof Number n) return new JsonPrimitive(n);
        if (legacyId instanceof Boolean b) return new JsonPrimitive(b);
        return JsonNull.INSTANCE; // matches nothing: real ids are never JSON null here
    }

    /**
     * The string form of a request id for {@link RpcContext#callId()}: the unquoted value of a
     * primitive ({@code "42"}, {@code "c1"}), falling back to the JSON serialization for any
     * exotic element. String ids — what the production host uses — are byte-identical to the
     * previous {@code Map}-view behaviour.
     */
    private static String idText(Object id) {
        return id instanceof JsonPrimitive p ? p.getAsString() : String.valueOf(id);
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

    /** Gson type for the handler-facing {@code params} map view of a request's params object. */
    private static final java.lang.reflect.Type PARAMS_TYPE =
        new TypeToken<Map<String, Object>>() {}.getType();

    /**
     * A parsed inbound frame. The id stays a raw {@link JsonElement} so the response can echo it
     * VERBATIM ({@code 42} stays {@code 42}, not Gson's {@code Map}-view {@code 42.0}); the params
     * are converted to the plain {@code Map<String, Object>} view handlers are written against.
     */
    private record ParsedRequest(JsonElement id, String method, Map<String, Object> params,
            JsonObject raw) {}

    @SuppressWarnings("unchecked")
    private ParsedRequest parseRequest(String line) {
        try (java.io.StringReader text = new java.io.StringReader(line);
             com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(text)) {
            // STRICT parsing: a bareword frame ("not-json") is invalid JSON -> -32700, exactly as
            // JSON-RPC 2.0 defines it; only frames that ARE valid JSON but not a request object
            // (a batch array, a bare primitive that strict accepts, e.g. 42) map to -32600.
            reader.setStrictness(com.google.gson.Strictness.STRICT);
            JsonElement tree = JsonParser.parseReader(reader);
            // parseReader tolerates trailing data ("{} junk"); the old fromJson(Map) path did
            // not — keep enforcing full-document consumption so a frame cannot smuggle a
            // second document after a valid one.
            if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) {
                throw new com.google.gson.JsonSyntaxException("Malformed JSON: trailing data");
            }
            if (!tree.isJsonObject()) {
                throw new RpcException(-32600, "Invalid Request", null);
            }
            JsonObject raw = tree.getAsJsonObject();
            JsonElement id = raw.has("id") && !raw.get("id").isJsonNull()
                ? raw.get("id") : null;
            if (!(raw.get("jsonrpc") instanceof JsonPrimitive version) || !version.isString()
                    || !"2.0".equals(version.getAsString())
                    || !(raw.get("method") instanceof JsonPrimitive methodElement)
                    || !methodElement.isString() || methodElement.getAsString().isBlank()) {
                throw new RpcException(-32600, "Invalid Request", id);
            }
            Map<String, Object> params = raw.get("params") instanceof JsonObject object
                ? (Map<String, Object>) json.fromJson(object, PARAMS_TYPE) : Map.of();
            return new ParsedRequest(id, methodElement.getAsString(), params, raw);
        } catch (StackOverflowError deeplyNested) {
            // A frame inside the byte cap can still nest past the parser's stack (megabytes of
            // '['). It is unparseable for our purposes: answer -32700, never kill the worker.
            throw new RpcException(-32700, "Parse error", null);
        } catch (JsonParseException | java.io.IOException e) {
            // IOException can only surface from closing the in-memory reader; either way the
            // frame is unparseable -> -32700.
            throw new RpcException(-32700, "Parse error", null);
        }
    }
}
