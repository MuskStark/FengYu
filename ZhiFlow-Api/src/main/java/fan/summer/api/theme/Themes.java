package fan.summer.api.theme;

import javafx.scene.Scene;

/**
 * SwissKitJ theme stylesheet loading utility.
 *
 * <p>Provides the common CSS stylesheet URL that defines the shared glassmorphism
 * utility classes (such as {@code .sk-dialog}, {@code .sk-field},
 * {@code .btn-primary}, {@code .btn-secondary}, and scrollbar styling). These
 * styles are declared once in the API module and are automatically available
 * to all plugins embedded in the main Scene.</p>
 *
 * <p>Plugins that create their own {@link Scene} or {@code Stage} (for example,
 * a modal dialog or a standalone tool window) should call {@link #applyTo(Scene)}
 * to ensure their nodes receive the same CSS classes. Calling this method on a
 * scene that already has the stylesheet loaded is a no-op.</p>
 *
 * <p>Plugins embedded in the main window via {@code createView()} do not need
 * to call this class — the host application loads the stylesheet into the
 * root scene automatically.</p>
 *
 * @see Scene#getStylesheets()
 * @since 1.0
 */
public final class Themes {

    /** Resource path of the shared common stylesheet within the API JAR. */
    public static final String COMMON_CSS = "/css/swisskit-common.css";

    private Themes() {}

    /**
     * Returns the external form URL of the shared common stylesheet.
     *
     * <p>The returned string can be added directly to a {@link Scene}'s
     * stylesheet list via {@code scene.getStylesheets().add(url)}.</p>
     *
     * @return the stylesheet URL as a string
     */
    public static String commonStylesheetUrl() {
        return Themes.class.getResource(COMMON_CSS).toExternalForm();
    }

    /** Loads the common stylesheet onto the scene if not already present (no theme stamping). */
    static void loadCommonStylesheet(Scene scene) {
        if (scene == null) return;
        String url = commonStylesheetUrl();
        if (!scene.getStylesheets().contains(url)) {
            scene.getStylesheets().add(url);
        }
    }

    /**
     * Applies the common theme stylesheet to the given scene if not already present.
     * Loads the common stylesheet and stamps the active theme class on the scene root.
     *
     * <p>This is intended for plugins that open their own {@code Stage} or build
     * a separate {@code Scene} and need access to the shared CSS utility classes.
     * If the scene already has the stylesheet loaded, this method does nothing.</p>
     *
     * @param scene the {@link Scene} to apply the theme to; ignored if {@code null}
     */
    public static void applyTo(Scene scene) {
        ThemeService.registerScene(scene);
    }
}
