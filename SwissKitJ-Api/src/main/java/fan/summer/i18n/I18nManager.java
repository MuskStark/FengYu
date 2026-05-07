package fan.summer.i18n;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * Manages internationalization (i18n) for the application.
 * Provides message lookup with modular bundles and plugin bundle support.
 *
 * @author summer
 */
public class I18nManager {

    private static final Logger logger = LoggerFactory.getLogger(I18nManager.class);

    private static final String BUNDLE_BASE = "i18n.messages";
    private static final String BUNDLE_PAGES = "i18n.pages.messages_";
    private static final String LANGUAGE_KEY = "language";
    private static final String DEFAULT_LANGUAGE = "en";

    private static Language currentLanguage = Language.ENGLISH;
    private static ResourceBundle mainBundle;
    private static ResourceBundle[] pageBundles;
    private static final List<LocaleChangeListener> listeners = new ArrayList<>();
    private static final Map<String, ResourceBundle> pluginBundles = new HashMap<>();

    /**
     * Initializes the i18n system with the given language.
     *
     * @param language the initial language
     */
    public static void init(Language language) {
        currentLanguage = language != null ? language : Language.ENGLISH;
        loadBundles(currentLanguage);
        logger.info("I18nManager initialized with language: {}", currentLanguage.getCode());
    }

    /**
     * Loads all bundles for the given language.
     */
    private static void loadBundles(Language language) {
        Locale locale = language.toLocale();
        logger.info("loadBundles called with language={}, locale={}", language, locale);

        mainBundle = ResourceBundle.getBundle(BUNDLE_BASE, locale);
        logger.info("mainBundle locale={}, keys={}", mainBundle.getLocale(), mainBundle.keySet());

        String[] pageNames = {"welcome", "excel", "email", "setting"};
        pageBundles = new ResourceBundle[pageNames.length];
        for (int i = 0; i < pageNames.length; i++) {
            String baseName = BUNDLE_PAGES + pageNames[i];
            try {
                pageBundles[i] = ResourceBundle.getBundle(baseName, locale);
                logger.info("Loaded pageBundle[{}] ({}) with locale={}", i, baseName, pageBundles[i].getLocale());
            } catch (Exception e) {
                logger.warn("Failed to load {} with locale {}, falling back to English: {}", baseName, locale, e.getMessage());
                pageBundles[i] = ResourceBundle.getBundle(baseName, Locale.ENGLISH);
                logger.info("Loaded pageBundle[{}] ({}) with fallback locale={}", i, baseName, pageBundles[i].getLocale());
            }
        }
    }

    /**
     * Sets the application language and notifies all listeners.
     *
     * @param language new language
     */
    public static void setLanguage(Language language) {
        if (language == null || language == currentLanguage) {
            return;
        }

        currentLanguage = language;
        loadBundles(language);
        clearPluginBundles();
        notifyListeners();
        logger.info("Language changed to: {}", language.getCode());
    }

    /**
     * Gets the current language.
     *
     * @return current language
     */
    public static Language getCurrentLanguage() {
        return currentLanguage;
    }

    /**
     * Gets a message by key from the current bundle with fallback chain.
     * Fallback order: page bundles &gt; main bundle &gt; English main bundle &gt; key
     *
     * @param key message key
     * @return message text or key itself if not found
     */
    public static String get(String key) {
        if (pageBundles != null) {
            for (int i = 0; i < pageBundles.length; i++) {
                ResourceBundle bundle = pageBundles[i];
                if (bundle != null) {
                    try {
                        if (bundle.containsKey(key)) {
                            String value = bundle.getString(key);
                            logger.debug("I18n get '{}' from pageBundles[{}], locale={}, value={}", key, i, bundle.getLocale(), value);
                            return value;
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to get key '{}' from pageBundles[{}]: {}", key, i, e.getMessage());
                    }
                }
            }
        }

        try {
            if (mainBundle != null && mainBundle.containsKey(key)) {
                String value = mainBundle.getString(key);
                logger.debug("I18n get '{}' from mainBundle, locale={}, value={}", key, mainBundle.getLocale(), value);
                return value;
            }
        } catch (Exception e) {
            logger.warn("Failed to get key '{}' from mainBundle: {}", key, e.getMessage());
        }

        try {
            ResourceBundle englishBundle = ResourceBundle.getBundle(BUNDLE_BASE, Locale.ENGLISH);
            if (englishBundle.containsKey(key)) {
                String value = englishBundle.getString(key);
                logger.debug("I18n get '{}' from englishBundle, value={}", key, value);
                return value;
            }
        } catch (Exception e) {
            logger.warn("Failed to get key '{}' from englishBundle: {}", key, e.getMessage());
        }

        logger.warn("I18n key '{}' not found, returning key itself", key);
        return key;
    }

    /**
     * Gets a message by key with placeholders replaced.
     *
     * @param key message key
     * @param args placeholder values
     * @return formatted message text or key if not found
     */
    public static String get(String key, Object... args) {
        String template = get(key);
        try {
            return String.format(template, args);
        } catch (Exception e) {
            return template;
        }
    }

    /**
     * Gets a message from a plugin's resource bundle.
     * Loads the bundle from the plugin's classloader and caches it.
     * Fallback: requested locale &gt; English locale &gt; key itself
     *
     * @param baseName the base name of the resource bundle (e.g., "i18n.messages")
     * @param key the message key
     * @param pluginClassLoader the classloader to load the bundle from
     * @return message text or key itself if not found
     */
    public static String getPluginString(String baseName, String key, ClassLoader pluginClassLoader) {
        String cacheKey = baseName + "_" + currentLanguage.getCode();
        ResourceBundle bundle = pluginBundles.get(cacheKey);

        if (bundle == null) {
            try {
                bundle = ResourceBundle.getBundle(baseName, currentLanguage.toLocale(), pluginClassLoader);
                pluginBundles.put(cacheKey, bundle);
                logger.debug("Loaded plugin bundle: {} with locale {}", baseName, currentLanguage.toLocale());
            } catch (Exception e) {
                logger.warn("Failed to load plugin bundle {}: {}", baseName, e.getMessage());
                return key;
            }
        }

        try {
            if (bundle.containsKey(key)) {
                return bundle.getString(key);
            }
        } catch (Exception e) {
            logger.warn("Failed to get key '{}' from plugin bundle {}: {}", key, baseName, e.getMessage());
        }

        try {
            ResourceBundle englishBundle = ResourceBundle.getBundle(baseName, Locale.ENGLISH, pluginClassLoader);
            if (englishBundle.containsKey(key)) {
                return englishBundle.getString(key);
            }
        } catch (Exception e) {
            logger.warn("Failed to get key '{}' from plugin bundle {} English fallback: {}", key, baseName, e.getMessage());
        }

        return key;
    }

    /**
     * Clears the plugin bundle cache. Called when language changes.
     */
    public static void clearPluginBundles() {
        pluginBundles.clear();
    }

    /**
     * Adds a listener for locale changes.
     *
     * @param listener to add
     */
    public static void addListener(LocaleChangeListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Removes a listener.
     *
     * @param listener to remove
     */
    public static void removeListener(LocaleChangeListener listener) {
        listeners.remove(listener);
    }

    /**
     * Notifies all listeners of language change.
     */
    private static void notifyListeners() {
        for (LocaleChangeListener listener : new ArrayList<>(listeners)) {
            try {
                listener.onLocaleChanged(currentLanguage);
            } catch (Exception e) {
                logger.error("Error notifying locale listener", e);
            }
        }
    }

    /**
     * Gets the current locale.
     *
     * @return java.util.Locale for current language
     */
    public static Locale getCurrentLocale() {
        return currentLanguage.toLocale();
    }
}
