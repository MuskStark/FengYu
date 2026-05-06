package fan.summer.i18n;

import fan.summer.database.DatabaseInit;
import fan.summer.database.entity.AppSettingEntity;
import fan.summer.database.mapper.AppSettingMapper;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Manages internationalization (i18n) for the application.
 * Loads and saves language preference from database and provides message lookup.
 * Supports modular bundles with fallback to English.
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

    /**
     * Initializes the i18n system, loading saved language from database.
     */
    public static void init() {
        String savedLang = loadLanguageFromDb();
        currentLanguage = Language.fromCode(savedLang);
        loadBundles(currentLanguage);
        logger.info("I18nManager initialized with language: {}", currentLanguage.getCode());
    }

    /**
     * Loads all bundles for the given language.
     */
    private static void loadBundles(Language language) {
        Locale locale = language.toLocale();
        Locale englishLocale = Locale.ENGLISH;

        mainBundle = ResourceBundle.getBundle(BUNDLE_BASE, locale);

        // Load page-specific bundles: welcome, excel, email, setting
        String[] pageNames = {"welcome", "excel", "email", "setting"};
        pageBundles = new ResourceBundle[pageNames.length];
        for (int i = 0; i < pageNames.length; i++) {
            String baseName = BUNDLE_PAGES + pageNames[i];
            // Try current language, fallback to English
            try {
                pageBundles[i] = ResourceBundle.getBundle(baseName, locale);
            } catch (Exception e) {
                // Fallback to English
                pageBundles[i] = ResourceBundle.getBundle(baseName, englishLocale);
            }
        }
    }

    /**
     * Loads the saved language preference from database.
     *
     * @return language code or default
     */
    private static String loadLanguageFromDb() {
        if (!DatabaseInit.isInitialized()) {
            logger.warn("Database not initialized, using default language");
            return DEFAULT_LANGUAGE;
        }

        try (SqlSession session = DatabaseInit.getSqlSession()) {
            AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
            AppSettingEntity setting = mapper.selectByKey(LANGUAGE_KEY);
            if (setting != null && setting.getSettingValue() != null) {
                return setting.getSettingValue();
            }
        } catch (Exception e) {
            logger.warn("Failed to load language from database: {}", e.getMessage());
        }
        return DEFAULT_LANGUAGE;
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
        saveLanguageToDb(language.getCode());
        notifyListeners();
        logger.info("Language changed to: {}", language.getCode());
    }

    /**
     * Saves language to database.
     *
     * @param code language code
     */
    private static void saveLanguageToDb(String code) {
        if (!DatabaseInit.isInitialized()) {
            return;
        }

        try (SqlSession session = DatabaseInit.getSqlSession()) {
            AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
            AppSettingEntity setting = mapper.selectByKey(LANGUAGE_KEY);
            if (setting == null) {
                setting = new AppSettingEntity();
                setting.setSettingKey(LANGUAGE_KEY);
                setting.setSettingValue(code);
                mapper.insert(setting);
            } else {
                setting.setSettingValue(code);
                mapper.update(setting);
            }
            session.commit();
            logger.info("Language saved to database: {}", code);
        } catch (Exception e) {
            logger.error("Failed to save language to database", e);
        }
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
     * Fallback order: page bundles (welcome -> excel -> email -> setting) -> main bundle -> English main bundle -> key
     *
     * @param key message key
     * @return message text or key itself if not found
     */
    public static String get(String key) {
        // Try page bundles first
        if (pageBundles != null) {
            for (ResourceBundle bundle : pageBundles) {
                try {
                    if (bundle.containsKey(key)) {
                        return bundle.getString(key);
                    }
                } catch (Exception ignored) {
                }
            }
        }

        // Try main bundle
        try {
            return mainBundle.getString(key);
        } catch (Exception ignored) {
        }

        // Try English main bundle as last resort
        try {
            ResourceBundle englishBundle = ResourceBundle.getBundle(BUNDLE_BASE, Locale.ENGLISH);
            return englishBundle.getString(key);
        } catch (Exception ignored) {
        }

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
