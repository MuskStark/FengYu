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
 * method name plus the param KEYS only — never values, since a value may be a request-carried
 * secret) and at DEBUG on success, and at WARN with the throwable on failure. These reach the
 * host's {@code plugin-<id>-stderr} drain through the SDK's structured SLF4J provider, so plugin
 * activity is observable from the host console and the per-plugin log buffer without each handler
 * doing its own logging.
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
                // The throwable (with stack) goes to SLF4J for diagnostics, but the shared WARN
                // message uses only the exception TYPE — error.getMessage() can echo request values
                // (a parsed path, a body fragment), so it is not safe in the shared log channel.
                log.warn("{} handler failed for {}: {}", pluginName, method, error.getClass().getSimpleName(), error);
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
            // Shared WARN message uses the exception type only; getMessage() may carry request values.
            log.warn("{} operation failed: {}", pluginName, error.getClass().getSimpleName(), error);
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

    /**
     * Compact preview of the params map's KEYS for entry logs. Only the param names are recorded —
     * never the values — because a value may be a secret the request carries (an SMTP password, a
     * mail body, a token, a filesystem path). Truncating by length is not a safe redaction, so
     * values are omitted entirely. The env redactor only knows env-borne secrets, so it cannot
     * catch request-carried secrets here. This mirrors the host's "log param keys, not values"
     * policy (PluginProcessManager#paramKeys).
     *
     * @param params the RPC params map (nullable)
     * @return a stable, value-free preview such as {@code [accountId, folder, outputDirectory]}
     */
    protected static String abbreviateParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return "{}";
        return params.keySet().toString();
    }

    /** An operation that may throw a checked exception, so handlers can call IO methods directly. */
    @FunctionalInterface
    protected interface ThrowingOperation {
        Map<String, Object> run() throws Exception;
    }
}
