package fan.summer.i18n;

/**
 * Listener for locale change events.
 * Implementations receive notification when the application language changes.
 *
 * @author summer
 */
public interface LocaleChangeListener {

    /**
     * Called when the application locale has changed.
     *
     * @param newLanguage the newly selected language
     */
    void onLocaleChanged(Language newLanguage);
}
