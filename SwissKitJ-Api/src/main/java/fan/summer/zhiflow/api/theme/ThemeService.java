package fan.summer.zhiflow.api.theme;

import javafx.scene.Parent;
import javafx.scene.Scene;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Holds the current UI theme (DARK / LIGHT), applies it to registered scenes
 * via a style class on the scene root, and notifies listeners on change.
 *
 * <p>This class lives in the API module and has no database dependency. The
 * host application is responsible for loading/persisting the user's choice
 * and calling {@link #set(Theme)}. Looked-up color tokens ({@code -sk-*}) are
 * defined per theme in {@code zhiflow-common.css} under {@code .theme-dark}
 * and {@code .theme-light}; swapping the root class re-resolves every token.
 *
 * @since 3.2.0
 */
public final class ThemeService {

    /** Supported themes. */
    public enum Theme { DARK, LIGHT }

    private static volatile Theme current = Theme.DARK;

    private static final List<Scene> SCENES = new CopyOnWriteArrayList<>();
    private static final List<Consumer<Theme>> LISTENERS = new CopyOnWriteArrayList<>();

    private ThemeService() {}

    /** @return the currently active theme (never null). */
    public static Theme current() { return current; }

    /**
     * Switches the active theme: re-stamps the theme class on every registered
     * scene root and fires listeners. {@code null} is ignored.
     *
     * <p><b>Must be called on the JavaFX Application Thread.</b>
     */
    public static void set(Theme theme) {
        if (theme == null) return;
        current = theme;
        String cls = (theme == Theme.DARK) ? "theme-dark" : "theme-light";
        for (Scene s : SCENES) {
            if (s.getRoot() != null) applyClass(s.getRoot(), cls);
        }
        for (Consumer<Theme> l : LISTENERS) {
            try { l.accept(theme); } catch (Exception ignored) { /* listener faults must not break switching */ }
        }
    }

    /**
     * Loads the common stylesheet into the scene and stamps the current theme
     * class on its root. Idempotent.
     *
     * <p><b>Must be called on the JavaFX Application Thread.</b>
     */
    public static void registerScene(Scene scene) {
        if (scene == null) return;
        Themes.loadCommonStylesheet(scene);
        if (!SCENES.contains(scene)) SCENES.add(scene);
        if (scene.getRoot() != null) {
            applyClass(scene.getRoot(), current == Theme.DARK ? "theme-dark" : "theme-light");
        }
    }

    /** Registers a listener fired on every {@link #set(Theme)} call. */
    public static void onChange(Consumer<Theme> listener) {
        if (listener != null) LISTENERS.add(listener);
    }

    /** Removes a previously-registered listener. No-op if absent or null. */
    public static void removeListener(Consumer<Theme> listener) {
        if (listener != null) LISTENERS.remove(listener);
    }

    private static void applyClass(Parent root, String themeClass) {
        root.getStyleClass().removeAll("theme-dark", "theme-light");
        root.getStyleClass().add(themeClass);
    }
}
