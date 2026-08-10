package fan.summer.fengyu.sdk;

/**
 * Per-request worker locale, propagated by the host through a {@code locale} key in the JSON-RPC
 * {@code params} map and bound for the duration of each handler call by {@link JsonRpcWorker#serve}.
 *
 * <p>This mirrors the host-side {@code AiPermissionContext}/{@code ChatFileContext} ThreadLocal
 * pattern: the dispatcher {@link #set(String) sets} the locale before invoking a handler and
 * {@link #clear() clears} it in a {@code finally} block, so a handler resolves its locale via
 * {@link #current()} without changing the {@link PluginHandler} signature or the JSON-RPC envelope.
 *
 * <p>Locale collapses to the language subtag conventions used elsewhere in FengYu: only {@code en}
 * and {@code zh} are distinguished (any tag starting with {@code zh}, case-insensitive, selects
 * Chinese; everything else falls back to English). The default, when the host omits the key or a
 * legacy/third-party host never sends it, is {@code en} — so workers without localized bundles keep
 * their prior English behaviour.
 *
 * @since 1.3.0
 */
public final class WorkerLocale {

    private static final ThreadLocal<String> CURRENT = new InheritableThreadLocal<>();
    private static final String DEFAULT_LOCALE = "en";

    private WorkerLocale() {}

    /** Bind the locale for the current handler call. {@code null} or blank resolves to {@code en}. */
    public static void set(String locale) {
        CURRENT.set(normalize(locale));
    }

    /**
     * The effective locale for the current handler call ({@code "en"} or {@code "zh"}). Always
     * non-null — safe to call outside a handler call (returns the default).
     */
    public static String current() {
        String locale = CURRENT.get();
        return locale == null ? DEFAULT_LOCALE : locale;
    }

    /** Unbind the locale. Idempotent; safe to call when nothing is bound. */
    public static void clear() {
        CURRENT.remove();
    }

    /** Collapse a raw locale tag to the supported {@code en}/{@code zh} code. */
    private static String normalize(String locale) {
        if (locale == null || locale.isBlank()) return DEFAULT_LOCALE;
        return locale.trim().toLowerCase(java.util.Locale.ROOT).startsWith("zh") ? "zh" : DEFAULT_LOCALE;
    }
}
