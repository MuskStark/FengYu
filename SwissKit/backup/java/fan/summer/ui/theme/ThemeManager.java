package fan.summer.ui.theme;

import fan.summer.i18n.I18nManager;
import fan.summer.i18n.Language;
import fan.summer.i18n.LocaleChangeListener;
import javafx.scene.Parent;

import java.net.URL;

public final class ThemeManager implements LocaleChangeListener {
    private static final ThemeManager INSTANCE = new ThemeManager();
    private static final String THEME_CSS = "/css/swisskit.css";
    private static final String APP_CSS = "/css/app.css";

    private ThemeManager() {
    }

    public static ThemeManager getInstance() {
        return INSTANCE;
    }

    /**
     * Initialize the theme manager.
     */
    public static void init() {
        I18nManager.addListener(INSTANCE);
        INSTANCE.applyDefaultTheme();
    }

    private void applyDefaultTheme() {
        // Apply default theme settings
    }

    /**
     * Apply theme to a parent node.
     */
    public void applyTheme(Parent root) {
        URL cssUrl = getClass().getResource(THEME_CSS);
        if (cssUrl != null) {
            root.getStylesheets().add(cssUrl.toExternalForm());
        }
    }

    /**
     * Apply both the base theme and app theme.
     */
    public void applyAppTheme(Parent root) {
        URL themeUrl = getClass().getResource(THEME_CSS);
        if (themeUrl != null) {
            root.getStylesheets().add(themeUrl.toExternalForm());
        }
        URL appUrl = getClass().getResource(APP_CSS);
        if (appUrl != null) {
            root.getStylesheets().add(appUrl.toExternalForm());
        }
    }

    public void applyTheme(Parent root, String additionalCss) {
        applyTheme(root);
        if (additionalCss != null && !additionalCss.isEmpty()) {
            URL additionalUrl = getClass().getResource(additionalCss);
            if (additionalUrl != null) {
                root.getStylesheets().add(additionalUrl.toExternalForm());
            }
        }
    }

    @Override
    public void onLocaleChanged(Language newLanguage) {
        // Refresh theme if needed based on language
    }
}