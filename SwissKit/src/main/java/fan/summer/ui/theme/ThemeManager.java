package fan.summer.ui.theme;

import javafx.scene.Parent;

import java.net.URL;

public final class ThemeManager {
    private static final ThemeManager INSTANCE = new ThemeManager();
    private static final String THEME_CSS = "/css/swisskit.css";

    private ThemeManager() {
    }

    public static ThemeManager getInstance() {
        return INSTANCE;
    }

    public void applyTheme(Parent root) {
        URL cssUrl = getClass().getResource(THEME_CSS);
        if (cssUrl != null) {
            root.getStylesheets().add(cssUrl.toExternalForm());
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
}