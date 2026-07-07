package fan.summer.api.host;

import javafx.beans.property.StringProperty;

/**
 * i18n access bound to one plugin. The key improvement over the static
 * {@code I18n} entry points: {@link #registerBundle(String)} resolves the
 * plugin's own ClassLoader automatically.
 *
 * @since 3.2.0
 */
public interface I18nFacade {

    /**
     * @param key  the message key
     * @param args optional MessageFormat arguments
     * @return the localized message, or the key itself if unresolved
     */
    String get(String key, Object... args);

    /**
     * Binds a StringProperty to a message key; updates live on locale change.
     *
     * @param property the property to bind
     * @param key      the message key
     */
    void bind(StringProperty property, String key);

    /**
     * Registers the plugin's message bundle using the PLUGIN'S OWN ClassLoader,
     * resolved automatically — no ClassLoader parameter, no way to get it wrong.
     * Call once, typically at the top of {@code createView()}.
     *
     * @param baseName the bundle base name, e.g. {@code "i18n.messages"}
     */
    void registerBundle(String baseName);

    /**
     * @param onLocaleChanged invoked (on the FX thread) whenever the locale changes
     */
    void addListener(Runnable onLocaleChanged);
}
