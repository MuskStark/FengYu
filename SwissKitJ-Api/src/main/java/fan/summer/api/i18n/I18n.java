package fan.summer.api.i18n;

import javafx.application.Platform;
import javafx.beans.property.StringProperty;

import java.lang.ref.WeakReference;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

public final class I18n {

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
        } catch (MissingResourceException ignored) {}
    }

    public static void registerPluginBundle(String baseName, ClassLoader loader) {
        try {
            ResourceBundle bundle = ResourceBundle.getBundle(baseName, currentLocale, loader);
            pluginBundles.put(loader, bundle);
        } catch (MissingResourceException ignored) {}
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
            ResourceBundle old = entry.getValue();
            try {
                ResourceBundle newBundle = ResourceBundle.getBundle(old.getBaseBundleName() != null
                        ? old.getBaseBundleName() : "i18n.messages", currentLocale, cl);
                pluginBundles.put(cl, newBundle);
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
}
