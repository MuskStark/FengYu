package fan.summer.fengyu.sdk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared base for official plugin RPC handler classes. Consolidates the {success, summary, ...}
 * result envelope, the failure-flattening {@code safe()} / {@code result()} wrappers, and uniform
 * entry/exit logging that previously had to be copy-pasted into every plugin.
 *
 * <p><b>Logging.</b> {@link #handle(String, PluginHandler)} logs every call at DEBUG on entry (the
 * method name plus an abbreviated param preview) and at DEBUG on success, and at WARN with the full
 * throwable on failure. These reach the host's {@code plugin-<id>-stderr} drain via the worker's
 * slf4j-simple binding, so plugin activity is observable from the host console and the per-plugin
 * log buffer without each handler doing its own logging.
 *
 * <p><b>Result envelope.</b> Handlers return a {@code Map} produced by {@link #ok}/{@link #failure};
 * {@link #handle} and {@link #result} guarantee every call resolves to such a map, converting any
 * thrown exception into a {@code {success:false, summary}} envelope.
 *
 * @since 1.2.0
 */
public abstract class PluginHandlerSupport {

    /** Human-readable plugin name used in log lines and default failure summaries. */
    protected final String pluginName;
    /** Logger named after the concrete subclass so logs carry the real handler class. */
    protected final Logger log;

    protected PluginHandlerSupport(String pluginName) {
        this.pluginName = pluginName;
        this.log = LoggerFactory.getLogger(getClass());
    }

    /**
     * Wrap a handler so every result follows the {success, summary, ...} envelope and so the call
     * is logged at entry/exit. This is what {@code WorkerMain} classes register via
     * {@code worker.on("method", handlers.handle("method", handlers::method))}.
     */
    public PluginHandler handle(String method, PluginHandler handler) {
        return params -> {
            log.debug("{} -> {} params={}", pluginName, method, abbreviateParams(params));
            try {
                Object value = handler.handle(params);
                Map<String, Object> envelope = value instanceof Map<?, ?> map ? cast(map) : ok("ok", null, value);
                log.debug("{} <- {} ok: {}", pluginName, method, envelope.get("summary"));
                return envelope;
            } catch (Exception error) {
                log.warn("{} handler failed for {}: {}", pluginName, method, String.valueOf(error.getMessage()), error);
                return failure(safeMessage(error));
            }
        };
    }

    /**
     * Run an operation that may throw, flattening it into the result envelope. Handlers that already
     * return success/failure envelopes via {@link #ok}/{@link #failure} should call this to keep a
     * thrown exception from escaping as an RPC error.
     */
    protected Map<String, Object> result(ThrowingOperation operation) {
        try {
            return operation.run();
        } catch (Exception error) {
            log.warn("{} operation failed: {}", pluginName, String.valueOf(error.getMessage()), error);
            return failure(safeMessage(error));
        }
    }

    // ── envelope builders ───────────────────────────────────────────────

    protected Map<String, Object> ok(String summary) {
        // Build directly rather than dispatching to ok(summary, null, null): subclasses override
        // the 3-arg form to JSON-encode values, and dispatching there would re-enter ok(summary)
        // and recurse without bound.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("summary", summary);
        return result;
    }

    protected Map<String, Object> ok(String summary, String key, Object value) {
        Map<String, Object> result = ok(summary);
        if (key != null) result.put(key, value);
        return result;
    }

    protected Map<String, Object> failure(String summary) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("summary", summary == null || summary.isBlank() ? "operation failed" : summary);
        return result;
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> cast(Object value) {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new IllegalArgumentException("Handler returned an invalid result");
    }

    /** One-line, throwable→message conversion that strips newlines so the summary stays single-line. */
    protected String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return pluginName + " operation failed";
        return message.replace('\r', ' ').replace('\n', ' ');
    }

    /** Compact preview of the params map for entry logs; large/unknown values are elided. */
    protected static String abbreviateParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return "{}";
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (!first) out.append(", ");
            first = false;
            out.append(entry.getKey()).append('=');
            Object value = entry.getValue();
            String rendered = value == null ? "null" : value.toString();
            if (rendered.length() > 60) rendered = rendered.substring(0, 57) + "...";
            out.append(rendered);
        }
        out.append('}');
        return out.toString();
    }

    /** An operation that may throw a checked exception, so handlers can call IO methods directly. */
    @FunctionalInterface
    protected interface ThrowingOperation {
        Map<String, Object> run() throws Exception;
    }
}
