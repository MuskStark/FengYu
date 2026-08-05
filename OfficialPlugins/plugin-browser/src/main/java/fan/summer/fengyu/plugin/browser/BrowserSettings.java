package fan.summer.fengyu.plugin.browser;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the worker's persisted settings from {@code <dataDir>/settings.json}.
 *
 * <p><b>Why this exists.</b> {@link ChromiumResolver} resolves the browser executable in three tiers;
 * Tier 1 honours a user-configured path supplied via a {@code Supplier<String>}. The worker previously
 * hardcoded {@code () -> null} at the call site, making Tier 1 permanently unreachable: the UI posts
 * {@code fengyu:setBrowserPath} but nothing read it back, so a user who set a path was silently
 * ignored and {@code ~150MB} of Chromium was auto-downloaded instead.
 *
 * <p>This class reads the {@code browserPath} field from the on-disk settings file so a real
 * supplier can be wired into {@link ChromiumResolver#forEnvironment(Path, java.util.function.Supplier)}.
 * The read is intentionally fault-tolerant: a missing, malformed, or blank-valued file resolves to
 * {@code null}, so the resolver falls through to the on-disk cache / auto-download rather than
 * crashing the worker.
 *
 * <p><b>MVP scope.</b> End-to-end UI&#x2192;worker wiring (a host-surfaced {@code browser_set_path}
 * JSON-RPC method that writes this file) is a follow-up — the postMessage bridge to a settings
 * endpoint does not exist yet. For now the file may be dropped in manually by a user or admin, or
 * written by a future UI integration; the worker simply reads it on startup.
 */
public final class BrowserSettings {

    private static final Logger log = LoggerFactory.getLogger(BrowserSettings.class);

    /** Settings file name, relative to the plugin data dir. */
    static final String SETTINGS_FILE = "settings.json";
    /** JSON field holding the user-configured browser executable path. */
    static final String BROWSER_PATH_FIELD = "browserPath";

    private static final Gson GSON = new Gson();

    private BrowserSettings() {}

    /**
     * Read the user-configured browser path from {@code <dataDir>/settings.json}.
     *
     * @param dataDir the plugin data dir (the directory that also holds {@code profile/},
     *                {@code chromium/}, {@code screenshots/}); not null
     * @return the {@code browserPath} value when the file exists and the field is a non-blank
     *         string; otherwise {@code null} (so Tier 1 is skipped and the resolver proceeds to
     *         the on-disk cache / auto-download). Never throws.
     */
    static String readBrowserPath(Path dataDir) {
        Path file = dataDir.resolve(SETTINGS_FILE);
        String contents;
        try {
            contents = Files.readString(file);
        } catch (IOException missing) {
            // Missing or unreadable file is the normal first-run case — not an error.
            return null;
        }
        try {
            JsonObject root = GSON.fromJson(contents, JsonObject.class);
            if (root == null || !root.has(BROWSER_PATH_FIELD)) return null;
            String value = root.get(BROWSER_PATH_FIELD).isJsonNull()
                    ? null : root.get(BROWSER_PATH_FIELD).getAsString();
            return (value == null || value.isBlank()) ? null : value;
        } catch (JsonSyntaxException | UnsupportedOperationException malformed) {
            // A bad settings file must never sink the worker; log and skip Tier 1.
            log.warn("Ignoring malformed {}: {}", file, malformed.getMessage());
            return null;
        }
    }
}
