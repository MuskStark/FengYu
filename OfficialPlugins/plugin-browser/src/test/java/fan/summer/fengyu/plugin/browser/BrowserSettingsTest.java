package fan.summer.fengyu.plugin.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies {@link BrowserSettings#readBrowserPath(Path)}: the worker now reads the user-configured
 * browser path from {@code <dataDir>/settings.json} (instead of hardcoding {@code () -> null} at the
 * {@link ChromiumResolver} call site). The helper must be tolerant — a missing, malformed, or
 * blank-valued file resolves to {@code null} (Tier 1 skipped) rather than throwing, so the resolver
 * falls through to the on-disk cache or auto-download instead of crashing the worker.
 */
class BrowserSettingsTest {

    @Test
    void returnsPathWhenSettingsFileHasBrowserPath(@TempDir Path dataDir) throws Exception {
        Files.writeString(dataDir.resolve("settings.json"),
                "{\"browserPath\": \"/some/path\"}");
        assertEquals("/some/path", BrowserSettings.readBrowserPath(dataDir));
    }

    @Test
    void returnsNullWhenSettingsFileMissing(@TempDir Path dataDir) {
        // dataDir exists but contains no settings.json — never throw, just fall through.
        assertNull(BrowserSettings.readBrowserPath(dataDir));
    }

    @Test
    void returnsNullWhenSettingsFileIsMalformedJson(@TempDir Path dataDir) throws Exception {
        Files.writeString(dataDir.resolve("settings.json"), "{not valid json");
        assertNull(BrowserSettings.readBrowserPath(dataDir),
                "malformed JSON must not propagate as an exception");
    }

    @Test
    void returnsNullWhenBrowserPathIsBlank(@TempDir Path dataDir) throws Exception {
        Files.writeString(dataDir.resolve("settings.json"),
                "{\"browserPath\": \"   \"}");
        assertNull(BrowserSettings.readBrowserPath(dataDir),
                "a blank browserPath is treated as unset");
    }

    @Test
    void returnsNullWhenBrowserPathKeyAbsent(@TempDir Path dataDir) throws Exception {
        // A valid settings file that simply does not declare a browserPath (e.g. a future schema
        // carrying unrelated keys) must resolve to null.
        Files.writeString(dataDir.resolve("settings.json"), "{\"otherKey\": 42}");
        assertNull(BrowserSettings.readBrowserPath(dataDir));
    }
}
