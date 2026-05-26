package fan.summer.api.i18n;

import javafx.application.Platform;
import javafx.beans.property.StringProperty;

import java.lang.ref.WeakReference;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

public final class I18n {

    private static final PluginLogger log = LoggerFactory.getLogger(I18n.class);

    private I18n() {}

    private static final AtomicReference<ResourceBundle> hostBundle = new AtomicReference<>();
    private static final ConcurrentHashMap<ClassLoader, ResourceBundle> pluginBundles = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<BoundEntry> bindings = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static volatile Locale currentLocale = Locale.ENGLISH;
    private static String hostBaseName;
    private static ClassLoader hostLoader;

    // ── Core lookup ──────────────────────────────────────────

    public static String get(String key) {
        ResourceBundle hb = hostBundle.get();
        if (hb != null && hb.containsKey(key)) return hb.getString(key);
        for (ResourceBundle pb : pluginBundles.values()) {
            if (pb.containsKey(key)) return pb.getString(key);
        }
        log.trace("i18n key not found: {}", key);
        return key;
    }

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

    public static void bind(StringProperty property, String key) {
        property.set(get(key));
        bindings.add(new BoundEntry(new WeakReference<>(property), key, null));
    }

    public static void bind(StringProperty property, String key, Object... args) {
        property.set(get(key, args));
        bindings.add(new BoundEntry(new WeakReference<>(property), key, args));
    }

    // ── Locale management ────────────────────────────────────

    public static Locale getLocale() {
        return currentLocale;
    }

    public static void setLocale(Locale locale) {
        if (locale == null) locale = Locale.ENGLISH;
        currentLocale = locale;
        rebuildHostBundle();
        rebuildPluginBundles();
        if (Platform.isFxApplicationThread()) {
            updateAll();
        } else {
            Platform.runLater(I18n::updateAll);
        }
    }

    // ── Listeners for manual refresh ─────────────────────────

    public static void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    // ── Bundle registration ──────────────────────────────────

    public static void registerBundle(String baseName, ClassLoader loader) {
        hostBaseName = baseName;
        hostLoader = loader;
        try {
            ResourceBundle bundle = ResourceBundle.getBundle(baseName, currentLocale, loader);
            hostBundle.set(bundle);
            log.info("Registered host i18n bundle: baseName={}, locale={}, keys={}",
                    baseName, currentLocale, bundle.keySet().size());
        } catch (MissingResourceException e) {
            log.warn("Failed to load host i18n bundle: baseName={}, locale={}, error={}",
                    baseName, currentLocale, e.getMessage());
        }
    }

    public static void registerPluginBundle(String baseName, ClassLoader loader) {
        try {
            ResourceBundle bundle;
            if (loader instanceof java.net.URLClassLoader ucl) {
                bundle = loadBundleFromUrlClassLoader(ucl, baseName, currentLocale);
            } else {
                bundle = ResourceBundle.getBundle(baseName, currentLocale, loader);
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

    public static void unregisterPluginBundle(ClassLoader loader) {
        pluginBundles.remove(loader);
    }

    // ── Internal ─────────────────────────────────────────────

    private static void rebuildHostBundle() {
        if (hostBaseName == null || hostLoader == null) return;
        try {
            ResourceBundle.clearCache(hostLoader);
            hostBundle.set(ResourceBundle.getBundle(hostBaseName, currentLocale, hostLoader));
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
                    newBundle = ResourceBundle.getBundle(baseName, currentLocale, cl);
                }
                if (newBundle != null) {
                    pluginBundles.put(cl, newBundle);
                }
            } catch (MissingResourceException ignored) {}
        }
    }

    private static void updateAll() {
        bindings.removeIf(e -> e.propertyRef().get() == null);
        for (BoundEntry e : bindings) {
            e.update();
        }
        for (Runnable l : listeners) {
            l.run();
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
