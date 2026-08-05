package fan.summer.fengyu.plugin.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType.LaunchPersistentContextOptions;
import com.microsoft.playwright.Playwright;

import java.nio.file.Path;
import java.util.List;

/**
 * Production {@link BrowserLauncher}: starts a persistent Chromium context via
 * {@code playwright.chromium().launchPersistentContext(userDataDir, options)}.
 *
 * <p><b>Why a persistent context (not {@code launch}).</b> The plugin keeps a real user profile
 * (cookies, login state, cache) under {@code <dataDir>/profile} so the AI's browsing session
 * survives worker restarts. {@code launchPersistentContext} returns a {@link BrowserContext}
 * directly — it never goes through {@code com.microsoft.playwright.Browser} — and that context
 * owns the Chromium process tree; closing it terminates all renderer/GPU/utility children.
 *
 * <p><b>Window size.</b> The {@code --window-size=1280,900} arg sets the initial Chromium window
 * dimensions; the screenshot handler reports the actual viewport captured from the PNG IHDR.
 *
 * <p><b>Executable.</b> When {@code executablePath} is non-blank it overrides Playwright's
 * bundled Chromium (e.g. to drive a system Chrome/Edge resolved by {@link ChromiumResolver});
 * otherwise Playwright's bundled/installed browser is used.
 */
public final class PlaywrightBrowserLauncher implements BrowserLauncher {

    /** Chromium launch args applied to every persistent context this launcher starts. */
    static final List<String> DEFAULT_ARGS = List.of("--window-size=1280,900");

    @Override
    public BrowserContext launch(Playwright playwright, Path userDataDir,
                                 String executablePath, boolean headless) {
        LaunchPersistentContextOptions opts = new LaunchPersistentContextOptions()
                .setHeadless(headless)
                .setArgs(DEFAULT_ARGS);
        if (executablePath != null && !executablePath.isBlank()) {
            opts.setExecutablePath(Path.of(executablePath));
        }
        return playwright.chromium().launchPersistentContext(userDataDir, opts);
    }
}
