package fan.summer.fengyu.sdk;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Small, dependency-light JSON-RPC 2.0 worker runtime for FengYu child processes. */
public final class JsonRpcWorker {
    private final Gson json = new Gson();
    private final Map<String, PluginHandler> handlers = new ConcurrentHashMap<>();

    public JsonRpcWorker on(String method, PluginHandler handler) {
        if (method == null || method.isBlank()) throw new IllegalArgumentException("method is required");
        if (handlers.putIfAbsent(method, handler) != null) throw new IllegalArgumentException("duplicate method: " + method);
        return this;
    }

    public void run() throws Exception {
        InputStream protocolInput = System.in;
        OutputStream protocolOutput = System.out;
        System.setOut(System.err);
        run(protocolInput, protocolOutput);
    }

    public void run(InputStream input, OutputStream output) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(output, true, StandardCharsets.UTF_8)) {
            for (String line; (line = reader.readLine()) != null;) {
                Map<String, Object> response = new LinkedHashMap<>(); response.put("jsonrpc", "2.0");
                try {
                    Map<String, Object> request = json.fromJson(line, new TypeToken<Map<String, Object>>() {}.getType());
                    response.put("id", request.get("id"));
                    String method = String.valueOf(request.get("method"));
                    PluginHandler handler = handlers.get(method);
                    if (handler == null) throw new RpcException(-32601, "Unknown method: " + method);
                    @SuppressWarnings("unchecked") Map<String, Object> params = request.get("params") instanceof Map<?, ?> map
                        ? (Map<String, Object>) map : Map.of();
                    response.put("result", handler.handle(params));
                } catch (RpcException e) {
                    response.put("error", Map.of("code", e.code(), "message", e.getMessage()));
                } catch (Exception e) {
                    response.put("error", Map.of("code", -32000, "message", String.valueOf(e.getMessage())));
                }
                writer.println(json.toJson(response));
            }
        }
    }

    public static String string(Map<String, Object> params, String key) {
        Object value = params.get(key); return value == null ? null : value.toString();
    }
    public static int integer(Map<String, Object> params, String key, int fallback) {
        Object value = params.get(key); return value instanceof Number number ? number.intValue() : fallback;
    }

    public static final class RpcException extends RuntimeException {
        private final int code;
        public RpcException(int code, String message) { super(message); this.code = code; }
        public int code() { return code; }
    }
}
