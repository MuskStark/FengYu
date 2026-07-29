package fan.summer.fengyu.ai.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class ThemeServiceTest {

    private Consumer<ThemeService.Theme> registered;

    @AfterEach
    void resetThemeService() {
        if (registered != null) {
            ThemeService.removeListener(registered);
            registered = null;
        }
        ThemeService.set(ThemeService.Theme.DARK);
    }

    @Test
    void defaultThemeIsDark() {
        ThemeService.set(ThemeService.Theme.DARK);
        assertEquals(ThemeService.Theme.DARK, ThemeService.current());
    }

    @Test
    void setNotifiesListeners() {
        AtomicReference<ThemeService.Theme> seen = new AtomicReference<>();
        Consumer<ThemeService.Theme> listener = t -> seen.set(t);
        registered = listener;
        ThemeService.onChange(listener);
        ThemeService.set(ThemeService.Theme.LIGHT);
        assertEquals(ThemeService.Theme.LIGHT, seen.get());
        assertEquals(ThemeService.Theme.LIGHT, ThemeService.current());
    }

    @Test
    void setNullIsNoOp() {
        ThemeService.set(ThemeService.Theme.DARK);
        ThemeService.set(null);
        assertEquals(ThemeService.Theme.DARK, ThemeService.current());
    }
}
