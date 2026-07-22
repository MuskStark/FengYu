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

/** Small, dependency-light JSON-RPC 2.0 worker runtime for FengYu child processes. */
public final class JsonRpcWorker {
    private static final Logger log = LoggerFactory.getLogger(JsonRpcWorker.class);
    private final Gson json = new Gson();
    private final Map<String, PluginHandler> handlers = new ConcurrentHashMap<>();

    public JsonRpcWorker on(String method, PluginHandler handler) {
        if (method == null || method.isBlank()) throw new IllegalArgumentException("method is required");
        java.util.Objects.requireNonNull(handler, "handler");
        if (handlers.putIfAbsent(method, handler) != null) throw new IllegalArgumentException("duplicate method: " + method);
        return this;
    }

    public void run() throws Exception {
        InputStream protocolInput = System.in;
        PrintStream protocolOutput = System.out;
        System.setOut(System.err);
        // Visible on stderr (so via the host's plugin-<id>-stderr drain) — confirms the worker
        // actually started. Without it a worker that exits before reading stdin leaves no trace.
        log.info("Plugin worker started");
        try {
            serve(new StdioTransport(protocolInput, protocolOutput));
        } finally {
            log.info("Plugin worker shutting down");
            System.setOut(protocolOutput);
        }
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
                response.put("id", requestId);
                method = (String) request.get("method");
                PluginHandler handler = handlers.get(method);
                if (handler == null) throw new RpcException(-32601, "Unknown method: " + method);
                @SuppressWarnings("unchecked") Map<String, Object> params = request.get("params") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
                response.put("result", handler.handle(params));
            } catch (RpcException e) {
                if (e.requestId() != null) response.put("id", e.requestId());
                response.put("error", Map.of("code", e.code(), "message", e.getMessage()));
            } catch (Exception e) {
                // Surface the failure so plugin failures stay diagnosable, but log only the method,
                // request id, exception type, and the (sanitized) exception message — never the raw
                // frame, which carries the full params JSON. The exception message is the handler
                // author's own diagnostic (rarely a raw caller secret); collapsing its whitespace
                // stops a multi-line value from slipping past a single-line log filter. The stack
                // trace is attached so the cause is not lost. This reaches stderr via the slf4j-simple
                // binding and the host's plugin-stderr drain.
                log.warn("Plugin worker dispatch failed for method={} id={}: {}",
                    method, requestId, e.getClass().getSimpleName());
                log.warn("Plugin worker dispatch failure detail for method={} id={}: {}",
                    method, requestId, sanitizeMessage(e.getMessage()), e);
                response.put("error", Map.of("code", -32000, "message", String.valueOf(e.getMessage())));
            }
            transport.writeFrame(json.toJson(response));
        }
    }

    /** Collapse newlines/tabs in an exception message to single spaces so a multi-line secret or
     *  stack fragment can't slip past a single-line log filter. Returns {@code "<no message>"} for null. */
    private static String sanitizeMessage(String message) {
        if (message == null) return "<no message>";
        return message.replaceAll("\\s+", " ");
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
