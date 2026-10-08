package fan.summer.fengyu.sdk;

/**
 * Per-request worker locale, propagated by the host through the reserved top-level
 * {@code _fengyu.locale} envelope (with a legacy fallback to a {@code locale} key in the JSON-RPC
 * {@code params} map for older hosts) and bound for the duration of each handler call by
 * {@link JsonRpcWorker#serve}.
 *
 * <p>This mirrors the host-side {@code AiPermissionContext}/{@code ChatFileContext} ThreadLocal
 * pattern: the dispatcher {@link #set(String) sets} the locale before invoking a handler and
 * {@link #clear()}s it in a {@code finally} block, so a handler resolves its locale via
 * {@link #current()} without changing the {@link PluginHandler} signature or the JSON-RPC envelope.
 *
 * <p>Locale collapses to the language subtag conventions used elsewhere in FengYu: only {@code en}
 * and {@code zh} are distinguished (any tag starting with {@code zh}, case-insensitive, selects
 * Chinese; everything else falls back to English). The default, when the host omits the key or a
 * legacy/third-party host never sends it, is {@code en} — so workers without localized bundles keep
 * their prior English behaviour.
 *
 * <h2>Threads you do not own</h2>
 * <p>The backing store is an {@code InheritableThreadLocal}: a thread created <em>while</em> a
 * handler runs inherits that call's locale, but a <em>reused</em> pooled thread does not re-inherit
 * on later submissions — it keeps whatever locale it was born with. When handing work to your own
 * executor, wrap the task with {@link #wrap(Runnable)} / {@link #wrap(Callable)} so the locale is
 * captured at submit time and bound around the task regardless of pool reuse.
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

    /**
     * Capture the CURRENT locale around a task handed to a thread you do not control (a plugin-owned
     * executor, a pooled scheduler). The returned runnable binds the captured locale for the
     * duration of the task and restores the executing thread's previous locale afterwards, so a
     * reused pool thread observes the submitting call's locale instead of the one it was created
     * with — the classic {@code InheritableThreadLocal} pool-reuse pitfall.
     */
    public static Runnable wrap(Runnable task) {
        String captured = current();
        return () -> {
            String previous = CURRENT.get();
            set(captured);
            try {
                task.run();
            } finally {
                restore(previous);
            }
        };
    }

    /** {@link #wrap(Runnable)} for {@link java.util.concurrent.Callable} tasks. */
    public static <T> java.util.concurrent.Callable<T> wrap(java.util.concurrent.Callable<T> task) {
        String captured = current();
        return () -> {
            String previous = CURRENT.get();
            set(captured);
            try {
                return task.call();
            } finally {
                restore(previous);
            }
        };
    }

    private static void restore(String previous) {
        if (previous == null) clear();
        else CURRENT.set(previous);
    }

    /** Collapse a raw locale tag to the supported {@code en}/{@code zh} code. */
    private static String normalize(String locale) {
        if (locale == null || locale.isBlank()) return DEFAULT_LOCALE;
        return locale.trim().toLowerCase(java.util.Locale.ROOT).startsWith("zh") ? "zh" : DEFAULT_LOCALE;
    }
}
