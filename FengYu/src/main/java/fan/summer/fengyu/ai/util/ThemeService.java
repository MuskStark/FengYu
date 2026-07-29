package fan.summer.fengyu.ai.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Holds the current theme used by host-side HTML renderers.
 */
public final class ThemeService {

    public enum Theme { DARK, LIGHT }

    private static volatile Theme current = Theme.DARK;
    private static final List<Consumer<Theme>> LISTENERS = new CopyOnWriteArrayList<>();

    private ThemeService() {
    }

    public static Theme current() {
        return current;
    }

    public static void set(Theme theme) {
        if (theme == null) {
            return;
        }
        current = theme;
        for (Consumer<Theme> listener : LISTENERS) {
            try {
                listener.accept(theme);
            } catch (RuntimeException ignored) {
                // A listener must not prevent the remaining listeners from receiving the change.
            }
        }
    }

    public static void onChange(Consumer<Theme> listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Consumer<Theme> listener) {
        if (listener != null) {
            LISTENERS.remove(listener);
        }
    }
}
