package fan.summer.fengyu.sdk;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves localized worker messages from a plugin's own classpath bundles
 * ({@code i18n/messages[_zh].properties}), keyed by stable codes instead of inline English literals.
 *
 * <p>Each official plugin ships its own {@code messages.properties} (English, the default and
 * fallback) and an optional {@code messages_zh.properties} (Chinese). A {@code PluginMessages}
 * instance is created once per worker (in {@link PluginHandlerSupport}) with the bundle base name
 * and the worker's classloader, and resolves every lookup against {@link WorkerLocale#current()}
 * so a single handler serves both locales without per-call plumbing.
 *
 * <p><b>Fallback policy.</b> The English bundle is the fallback: a missing Chinese value falls back
 * to English, and a missing English value falls back to the raw key (so a worker never throws on an
 * untranslated code — it renders the key, which is easy to spot and never crashes a handler).
 * {@link MessageFormat} interpolation runs only on the final resolved pattern; a key returned raw is
 * not re-interpreted.
 *
 * <p>Bundles are cached per locale in a {@link ConcurrentHashMap}; the underlying
 * {@link ResourceBundle#getBundle(String, Locale, ClassLoader)} also caches internally, so repeated
 * lookups are cheap.
 *
 * @since 1.3.0
 */
public final class PluginMessages {

    /** The conventional base name and location for plugin worker message bundles. */
    public static final String DEFAULT_BASE_NAME = "i18n.messages";

    private final String baseName;
    private final ClassLoader classLoader;
    private final ConcurrentHashMap<String, ResourceBundle> bundleCache = new ConcurrentHashMap<>();

    /** Load from the default base name ({@value #DEFAULT_BASE_NAME}) on the caller's classpath. */
    public static PluginMessages forClassLoader(String baseName, Class<?> owner) {
        ClassLoader loader = owner == null ? PluginMessages.class.getClassLoader() : owner.getClassLoader();
        return new PluginMessages(baseName == null || baseName.isBlank() ? DEFAULT_BASE_NAME : baseName, loader);
    }

    public PluginMessages(String baseName, ClassLoader classLoader) {
        this.baseName = baseName;
        this.classLoader = classLoader == null ? PluginMessages.class.getClassLoader() : classLoader;
    }

    /**
     * Resolve {@code key} for the current worker locale with {@code MessageFormat} interpolation of
     * positional {@code {0}}/{@code {1}}/… placeholders. Falls back to English, then to the raw key.
     */
    public String format(String key, Object... args) {
        String pattern = pattern(key);
        if (args == null || args.length == 0) return pattern;
        try {
            return new MessageFormat(pattern, localeFor(WorkerLocale.current())).format(args);
        } catch (IllegalArgumentException malformed) {
            // A broken placeholder pattern should not crash a handler — return the raw pattern.
            return pattern;
        }
    }

    /** Resolve {@code key} without interpolation. Same fallback chain as {@link #format}. */
    public String get(String key) {
        return pattern(key);
    }

    /** Resolve the pattern string for the current locale, falling back to English then the key. */
    private String pattern(String key) {
        String locale = WorkerLocale.current();
        ResourceBundle bundle = bundle(locale);
        if (bundle != null && bundle.containsKey(key)) return bundle.getString(key);
        // Fall back to English (the default bundle) when the localized bundle omits the key.
        if (!"en".equals(locale)) {
            ResourceBundle en = bundle("en");
            if (en != null && en.containsKey(key)) return en.getString(key);
        }
        return key;
    }

    private ResourceBundle bundle(String locale) {
        if (locale == null) locale = "en";
        return bundleCache.computeIfAbsent(locale, l -> loadBundle(l));
    }

    private ResourceBundle loadBundle(String locale) {
        Locale target = "zh".equals(locale) ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
        try {
            // The standard candidate fallback chain resolves a missing zh value to the root bundle
            // (messages.properties = English), which is exactly the desired fallback. Property files
            // are UTF-8 on Java 9+ (PropertyResourceBundle default), so CJK text decodes directly.
            return ResourceBundle.getBundle(baseName, target, classLoader);
        } catch (MissingResourceException none) {
            // No bundle at all (legacy/third-party worker without i18n) — callers fall back to keys.
            return null;
        }
    }

    private static Locale localeFor(String locale) {
        return "zh".equals(locale) ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
    }
}
