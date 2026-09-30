package fan.summer.fengyu.sdk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-call context bound to the handler thread for the duration of one JSON-RPC request. Mirrors
 * the {@code WorkerLocale} ThreadLocal pattern: the dispatch loop {@link #bind}s it before
 * invoking a handler and {@link #clear}s it in a finally, so a typed handler reads its call
 * identity, locale, plugin environment, and cancellation token through
 * {@code RpcContext.current()} without changing the handler signature.
 *
 * @since 1.4.0
 */
public final class RpcContext {
    private static final ThreadLocal<RpcContext> CURRENT = new ThreadLocal<>();

    private final String callId;
    private final String pluginId;
    private final String pluginRoot;
    private final String locale;
    private final CancellationToken cancellation;
    private final Logger logger;

    public RpcContext(String callId, String pluginId, String pluginRoot, String locale,
                      CancellationToken cancellation, Logger logger) {
        this.callId = callId;
        this.pluginId = pluginId;
        this.pluginRoot = pluginRoot;
        this.locale = locale;
        this.cancellation = cancellation;
        this.logger = logger;
    }

    /**
     * The JSON-RPC request id for this call. For agent runs this is also the stable logical-step
     * idempotency key and is reused after an interrupted resume; effectful handlers may persist it
     * to deduplicate an external commit.
     */
    public String callId() { return callId; }

    /** The plugin id the host injected via {@code FENGYU_PLUGIN_ID}, or {@code null}. */
    public String pluginId() { return pluginId; }

    /** The plugin's unpacked root directory ({@code FENGYU_PLUGIN_ROOT}), or {@code null}. */
    public String pluginRoot() { return pluginRoot; }

    /** The request locale the host bound for this call (e.g. {@code "en"}, {@code "zh"}). */
    public String locale() { return locale; }

    /** The cancellation token; check {@link CancellationToken#throwIfCancelled()} at checkpoints. */
    public CancellationToken cancellation() { return cancellation; }

    /** A structured logger whose output reaches stderr (and the host's plugin log drain). */
    public Logger logger() { return logger == null ? LoggerFactory.getLogger("plugin") : logger; }

    /** The context bound to the current handler thread, or {@code null} outside a handler. */
    public static RpcContext current() { return CURRENT.get(); }

    static void bind(RpcContext ctx) { CURRENT.set(ctx); }
    static void clear() { CURRENT.remove(); }
}
