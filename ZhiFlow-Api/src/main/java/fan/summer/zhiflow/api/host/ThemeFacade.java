package fan.summer.zhiflow.api.host;

import fan.summer.zhiflow.api.theme.ThemeService;
import javafx.scene.Scene;

import java.util.function.Consumer;

/**
 * Theme access for plugins: query the active theme, react to switches, and
 * theme plugin-owned Stages.
 *
 * @since 3.2.0
 */
public interface ThemeFacade {

    /** @return the currently active theme */
    ThemeService.Theme current();

    /** @param listener invoked whenever the theme changes */
    void onChange(Consumer<ThemeService.Theme> listener);

    /**
     * For plugin-owned Stages: loads the common stylesheet and stamps the active
     * theme class on the scene root so {@code -sk-*} tokens resolve.
     *
     * @param scene the scene of a plugin-created Stage
     */
    void applyTo(Scene scene);
}
