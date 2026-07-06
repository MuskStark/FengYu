package fan.summer.zhiflow.api.i18n;

import javafx.application.Platform;
import javafx.beans.property.StringProperty;

import java.lang.ref.WeakReference;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;

/**
 * Internationalization utility providing message lookup, JavaFX property binding,
 * and locale management for the ZhiFlow host application and its plugins.
 *
 * <p>I18n maintains two bundle scopes:</p>
 * <ul>
 *   <li><strong>Host bundle</strong> &mdash; loaded via {@link #registerBundle(String, ClassLoader)} and checked first
 *        on every lookup.</li>
 *   <li><strong>Plugin bundles</strong> &mdash; loaded via {@link #registerPluginBundle(String, ClassLoader)} and
 *        consulted in insertion order when the host bundle has no matching key.</li>
 * </ul>
 *
 * <p>Locale changes via {@link #setLocale(Locale)} automatically rebuild all bundles and refresh every
 * bound {@link StringProperty} and registered listener.</p>
 *
 * <p>All static methods are thread-safe. Callbacks registered via {@link #addListener(Runnable)} are
 * executed on the JavaFX Application Thread when a locale change originates off the FX thread.</p>
 *
 * @see ResourceBundle
 * @see Locale
 */
public final class I18n {

    private static final PluginLogger log = LoggerFactory.getLogger(I18n.class);

    private I18n() {}

    private static final AtomicReference<ResourceBundle> hostBundle = new AtomicReference<>();
    private static final ConcurrentHashMap<ClassLoader, ResourceBundle> pluginBundles = new ConcurrentHashMap<>();
    /**
     * Pre-loaded fallback bundles consulted after the host and plugin bundles.
     * Unlike {@link #pluginBundles}, these are registered as already-built
     * {@link ResourceBundle} instances (loaded directly from a known URL), so
     * lookup never depends on resolving a {@link ClassLoader} — important when
     * a module (e.g. ZhiFlow-Api) ships its own messages but is loaded under
     * a classloader where {@code ResourceBundle.getBundle} can't find them.
     */
    private static final CopyOnWriteArrayList<FallbackBundle> fallbackBundles = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<BoundEntry> bindings = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static volatile Locale currentLocale = Locale.ENGLISH;
    private static String hostBaseName;
    private static ClassLoader hostLoader;

    /** A fallback bundle paired with the base name + URL it was loaded from, so it can be reloaded on locale change. */
    private static final class FallbackBundle {
        final String baseName;
        final java.net.URL bundleUrl;
        volatile ResourceBundle bundle;
        FallbackBundle(String baseName, java.net.URL bundleUrl, ResourceBundle bundle) {
            this.baseName = baseName;
            this.bundleUrl = bundleUrl;
            this.bundle = bundle;
        }
    }

    /**
     * A {@link ResourceBundle.Control} that never falls back to the JVM default locale.
     * <p>
     * Without this, on a Chinese system {@code Locale.getDefault()} returns a Chinese locale,
     * causing {@code ResourceBundle.getBundle("i18n.messages", Locale.ENGLISH, loader)} to
     * include Chinese candidates in the lookup chain. Since there is no {@code messages_en.properties}
     * (English lives in the root bundle {@code messages.properties}), the Chinese bundle
     * {@code messages_zh.properties} becomes the most-specific match and is returned — even
     * though English was requested.
     * <p>
     * Returning {@code null} from {@link #getFallbackLocale} prevents the default locale from
     * being injected into the candidate list, so only the explicitly requested locale and ROOT
     * are considered.
     */
    @SuppressWarnings("serial")
    private static final ResourceBundle.Control NO_FALLBACK_CONTROL = new ResourceBundle.Control() {
        @Override
        public Locale getFallbackLocale(String baseName, Locale locale) {
            if (baseName == null || locale == null) {
                throw new NullPointerException();
            }
            return null; // never fall back to JVM default locale
        }
    };

    // ── Core lookup ──────────────────────────────────────────

    /**
     * Looks up a message key in the host bundle first, then in each plugin bundle in insertion order.
     * Returns the key itself if no bundle contains it.
     *
     * @param key the message key to look up
     * @return the resolved message string, or {@code key} if not found
     */
    public static String get(String key) {
        ResourceBundle hb = hostBundle.get();
        if (hb != null && hb.containsKey(key)) return hb.getString(key);
        for (ResourceBundle pb : pluginBundles.values()) {
            if (pb.containsKey(key)) return pb.getString(key);
        }
        for (FallbackBundle fb : fallbackBundles) {
            ResourceBundle b = fb.bundle;
            if (b != null && b.containsKey(key)) return b.getString(key);
        }
        log.trace("i18n key not found: {}", key);
        return key;
    }

    /**
     * Looks up a message key and formats it with the provided arguments using
     * {@link MessageFormat}. Falls back to the pattern string if formatting fails.
     *
     * @param key  the message key to look up
     * @param args positional arguments for the message pattern
     * @return the formatted message string, or the unresolved pattern if not found or if args is null/empty
     */
    public static String get(String key, Object... args) {
        String pattern = get(key);
        if (args == null || args.length == 0) return pattern;
        try {
            return MessageFormat.format(pattern, args);
        } catch (IllegalArgumentException e) {
            return pattern;
        }
    }

    // ── JavaFX reactive binding ──────────────────────────────

    /**
     * Binds a {@link StringProperty} to a message key so the property value stays in sync
     * with the current locale.
     *
     * <p>The property is set immediately with the current locale's message. When
     * {@link #setLocale(Locale)} is called, the property is updated automatically.</p>
     *
     * @param property the JavaFX property to bind
     * @param key     the message key
     */
    public static void bind(StringProperty property, String key) {
        property.set(get(key));
        bindings.add(new BoundEntry(new WeakReference<>(property), key, null));
    }

    /**
     * Binds a {@link StringProperty} to a formatted message so the property value stays in sync
     * with the current locale.
     *
     * <p>The property is set immediately with the current locale's formatted message. When
     * {@link #setLocale(Locale)} is called, the property is updated with fresh formatted text.</p>
     *
     * @param property the JavaFX property to bind
     * @param key      the message key
     * @param args     arguments used to format the message via {@link MessageFormat}
     */
    public static void bind(StringProperty property, String key, Object... args) {
        property.set(get(key, args));
        bindings.add(new BoundEntry(new WeakReference<>(property), key, args));
    }

    // ── Locale management ────────────────────────────────────

    /**
     * Returns the currently active {@link Locale}.
     *
     * @return the current locale
     */
    public static Locale getLocale() {
        return currentLocale;
    }

    /**
     * Changes the current locale, rebuilds both host and plugin resource bundles, and refreshes
     * all bound {@link StringProperty} instances and registered listeners.
     *
     * <p>If the current thread is the JavaFX Application Thread, refresh runs synchronously;
     * otherwise it is scheduled via {@link Platform#runLater(Runnable)}.</p>
     *
     * @param locale the new locale (null is treated as {@link Locale#ENGLISH})
     */
    public static void setLocale(Locale locale) {
        if (locale == null) locale = Locale.ENGLISH;
        currentLocale = locale;
        rebuildHostBundle();
        rebuildPluginBundles();
        rebuildFallbackBundles();
        if (Platform.isFxApplicationThread()) {
            updateAll();
        } else {
            Platform.runLater(I18n::updateAll);
        }
    }

    // ── Listeners for manual refresh ─────────────────────────

    /**
     * Registers a callback to be invoked whenever the locale changes.
     *
     * @param listener the runnable to invoke on locale change
     * @see #removeListener(Runnable)
     */
    public static void addListener(Runnable listener) {
        listeners.add(listener);
    }

    /**
     * Removes a previously registered locale-change listener.
     *
     * @param listener the listener to remove
     * @see #addListener(Runnable)
     */
    public static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    // ── Bundle registration ──────────────────────────────────

    /**
     * Registers the host application's main {@link ResourceBundle}, replacing any previously registered
     * host bundle. The bundle is loaded for the current locale and used as the primary lookup source.
     *
     * @param baseName the base name of the resource bundle (e.g. {@code "i18n.messages"})
     * @param loader   the class loader to load the bundle from
     */
    public static void registerBundle(String baseName, ClassLoader loader) {
        hostBaseName = baseName;
        hostLoader = loader;
        try {
            ResourceBundle bundle = ResourceBundle.getBundle(baseName, currentLocale, loader, NO_FALLBACK_CONTROL);
            hostBundle.set(bundle);
            log.info("Registered host i18n bundle: baseName={}, locale={}, keys={}",
                    baseName, currentLocale, bundle.keySet().size());
        } catch (MissingResourceException e) {
            log.warn("Failed to load host i18n bundle: baseName={}, locale={}, error={}",
                    baseName, currentLocale, e.getMessage());
        }
    }

    /**
     * Registers a plugin's {@link ResourceBundle} for supplemental i18n lookup.
     * Plugin bundles are consulted after the host bundle when resolving keys.
     *
     * <p>Supports both regular {@link ClassLoader} and {@link java.net.URLClassLoader} instances.
     * For URLClassLoader, resources are located via {@link java.net.URLClassLoader#findResource(String)}
     * to work around plugin-isolation restrictions.</p>
     *
     * @param baseName the base name of the resource bundle
     * @param loader   the class loader of the plugin
     * @see #unregisterPluginBundle(ClassLoader)
     */
    public static void registerPluginBundle(String baseName, ClassLoader loader) {
        try {
            ResourceBundle bundle;
            if (loader instanceof java.net.URLClassLoader ucl) {
                bundle = loadBundleFromUrlClassLoader(ucl, baseName, currentLocale);
            } else {
                bundle = ResourceBundle.getBundle(baseName, currentLocale, loader, NO_FALLBACK_CONTROL);
            }
            if (bundle != null) {
                pluginBundles.put(loader, bundle);
                log.debug("Registered plugin i18n bundle: baseName={}, locale={}, keys={}",
                        baseName, currentLocale, bundle.keySet().size());
            }
        } catch (MissingResourceException e) {
            log.warn("Failed to load plugin i18n bundle: baseName={}, locale={}, loader={}, error={}",
                    baseName, currentLocale, loader, e.getMessage());
        }
    }

    /**
     * Removes the plugin bundle associated with the given class loader.
     *
     * @param loader the plugin class loader whose bundle should be removed
     * @see #registerPluginBundle(String, ClassLoader)
     */
    public static void unregisterPluginBundle(ClassLoader loader) {
        pluginBundles.remove(loader);
    }

    /**
     * Registers a pre-located fallback bundle, loaded directly from {@code bundleUrl}.
     *
     * <p>Unlike {@link #registerPluginBundle(String, ClassLoader)}, this never relies
     * on resolving a {@link ClassLoader} via {@link ResourceBundle#getBundle} — it
     * reads the properties straight from the URL. This is the robust path for a
     * module (such as {@code ZhiFlow-Api}) that ships its own small message
     * bundle and must resolve it even when loaded under a classloader where the
     * standard {@code getBundle} lookup chain cannot see the resource.</p>
     *
     * <p>The bundle is loaded for the current locale. Locale-specific variants are
     * resolved by candidate filename ({@code baseName_ll.properties} →
     * {@code baseName.properties}) and reloaded automatically on
     * {@link #setLocale(Locale)}. Idempotent: a second registration with the same
     * URL replaces the prior one.</p>
     *
     * @param baseName  the resource base name, e.g. {@code "i18n.messages"} — used to
     *                  derive locale-variant filenames for reload
     * @param bundleUrl the URL of the default ({@code baseName.properties}) properties file
     * @return {@code true} if the bundle loaded successfully and was registered
     */
    public static boolean registerFallbackBundle(String baseName, java.net.URL bundleUrl) {
        if (baseName == null || bundleUrl == null) return false;
        ResourceBundle b = loadFallbackBundle(baseName, bundleUrl, currentLocale);
        if (b == null) {
            log.warn("Failed to load fallback i18n bundle: baseName={}, url={}", baseName, bundleUrl);
            return false;
        }
        // Replace any existing entry for the same base name/URL (idempotent).
        fallbackBundles.removeIf(fb -> bundleUrl.equals(fb.bundleUrl));
        fallbackBundles.add(new FallbackBundle(baseName, bundleUrl, b));
        log.info("Registered fallback i18n bundle: baseName={}, locale={}, keys={}",
                baseName, currentLocale, b.keySet().size());
        return true;
    }

    /**
     * Loads a locale-appropriate fallback bundle from the directory of
     * {@code defaultUrl}, trying {@code baseName_ll.properties} then
     * {@code baseName.properties}.
     */
    private static ResourceBundle loadFallbackBundle(String baseName, java.net.URL defaultUrl, Locale locale) {
        String external = defaultUrl.toExternalForm();
        String baseFile = external.substring(0, external.length() - ".properties".length());
        String ll = (locale != null && !locale.getLanguage().isEmpty())
                ? baseFile + "_" + locale.getLanguage() + ".properties" : null;
        String rootFile = baseFile + ".properties";
        String[] candidates = (ll != null) ? new String[]{ll, rootFile} : new String[]{rootFile};
        for (String candidate : candidates) {
            try {
                java.net.URL url = java.net.URI.create(candidate).toURL();
                try (java.io.InputStream is = url.openStream()) {
                    return new java.util.PropertyResourceBundle(is);
                }
            } catch (Exception ignored) { /* try next candidate */ }
        }
        return null;
    }

    // ── Internal ─────────────────────────────────────────────

    private static void rebuildHostBundle() {
        if (hostBaseName == null || hostLoader == null) return;
        try {
            ResourceBundle.clearCache(hostLoader);
            hostBundle.set(ResourceBundle.getBundle(hostBaseName, currentLocale, hostLoader, NO_FALLBACK_CONTROL));
        } catch (MissingResourceException ignored) {}
    }

    private static void rebuildPluginBundles() {
        for (Map.Entry<ClassLoader, ResourceBundle> entry : pluginBundles.entrySet()) {
            ClassLoader cl = entry.getKey();
            try {
                ResourceBundle newBundle;
                if (cl instanceof java.net.URLClassLoader ucl) {
                    newBundle = loadBundleFromUrlClassLoader(ucl, "i18n.messages", currentLocale);
                } else {
                    ResourceBundle old = entry.getValue();
                    String baseName = old.getBaseBundleName() != null
                            ? old.getBaseBundleName() : "i18n.messages";
                    newBundle = ResourceBundle.getBundle(baseName, currentLocale, cl, NO_FALLBACK_CONTROL);
                }
                if (newBundle != null) {
                    pluginBundles.put(cl, newBundle);
                }
            } catch (MissingResourceException ignored) {}
        }
    }

    /** Reloads every fallback bundle for the current locale (in place). */
    private static void rebuildFallbackBundles() {
        for (FallbackBundle fb : fallbackBundles) {
            ResourceBundle nb = loadFallbackBundle(fb.baseName, fb.bundleUrl, currentLocale);
            if (nb != null) {
                // Replace the record in the list (CopyOnWrite — rebuild the entry).
                fallbackBundles.remove(fb);
                fallbackBundles.add(new FallbackBundle(fb.baseName, fb.bundleUrl, nb));
            }
        }
    }

    private static final int MAX_STALE_BINDINGS = 200;

    private static void updateAll() {
        // Clean up GC'd bindings
        bindings.removeIf(e -> e.propertyRef().get() == null);
        for (BoundEntry e : bindings) {
            e.update();
        }
        for (Runnable l : listeners) {
            l.run();
        }
        // Safety: if the bindings list has grown excessively, force a full sweep
        if (bindings.size() > MAX_STALE_BINDINGS) {
            bindings.removeIf(e -> e.propertyRef().get() == null);
        }
    }

    private static ResourceBundle loadBundleFromUrlClassLoader(
            java.net.URLClassLoader ucl, String baseName, Locale locale) {
        String path = baseName.replace('.', '/');
        String[] candidates;
        if (locale != null && !locale.getLanguage().isEmpty()) {
            candidates = new String[]{
                path + "_" + locale.getLanguage() + ".properties",
                path + ".properties"
            };
        } else {
            candidates = new String[]{ path + ".properties" };
        }
        for (String resource : candidates) {
            java.net.URL url = ucl.findResource(resource);
            if (url != null) {
                try (java.io.InputStream is = url.openStream()) {
                    return new java.util.PropertyResourceBundle(is);
                } catch (Exception ignored) {}
            }
        }
        return null;
    }
}
