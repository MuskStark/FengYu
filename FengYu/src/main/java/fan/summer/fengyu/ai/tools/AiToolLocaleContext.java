package fan.summer.fengyu.ai.tools;

/**
 * Inheritable per-turn locale for plugin tool calls dispatched by the AI backends, mirroring
 * {@link AiPermissionContext}. The chat controller binds the request locale (resolved from the
 * {@code Accept-Language} header) for the duration of a turn so {@code AiToolRegistry} can pass it
 * to {@code PluginProcessManager.invoke(..., locale)} and plugins render localized summaries without
 * threading the locale through every tool signature.
 *
 * <p>{@link #current()} defaults to {@code "en"} when nothing is bound (e.g. a non-HTTP caller), so
 * AI-tool plugin calls preserve their prior English behaviour when no locale is known.
 */
public final class AiToolLocaleContext {
    private static final ThreadLocal<String> CURRENT = new InheritableThreadLocal<>();
    private static final String DEFAULT_LOCALE = "en";

    private AiToolLocaleContext() {}

    /** Bind the locale for the current turn. {@code null}/blank resolves to the default. */
    public static void set(String locale) {
        CURRENT.set(locale == null || locale.isBlank() ? DEFAULT_LOCALE : locale);
    }

    /** The effective locale for the current turn ({@code "en"} by default). Always non-null. */
    public static String current() {
        String locale = CURRENT.get();
        return locale == null ? DEFAULT_LOCALE : locale;
    }

    /** Unbind the locale for the current turn. Idempotent. */
    public static void clear() {
        CURRENT.remove();
    }
}
