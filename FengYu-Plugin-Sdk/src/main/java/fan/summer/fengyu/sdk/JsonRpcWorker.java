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
        try {
            serve(new StdioTransport(protocolInput, protocolOutput));
        } finally {
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
            try {
                Map<String, Object> request = parseRequest(line);
                response.put("id", request.get("id"));
                String method = (String) request.get("method");
                PluginHandler handler = handlers.get(method);
                if (handler == null) throw new RpcException(-32601, "Unknown method: " + method);
                @SuppressWarnings("unchecked") Map<String, Object> params = request.get("params") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
                response.put("result", handler.handle(params));
            } catch (RpcException e) {
                if (e.requestId() != null) response.put("id", e.requestId());
                response.put("error", Map.of("code", e.code(), "message", e.getMessage()));
            } catch (Exception e) {
                // Surface the failure (with stack trace) before flattening it into the RPC error
                // message. Otherwise the cause is lost and plugin failures are undiagnosable. This
                // reaches stderr via the slf4j-simple binding and the host's plugin-stderr drain.
                log.warn("Plugin worker dispatch failed for method in request: {}", line, e);
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
