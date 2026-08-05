package fan.summer.fengyu.plugin.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Playwright;

import java.nio.file.Path;

/**
 * Seam that starts Playwright and launches a persistent browser context.
 *
 * <p>Production uses {@code PlaywrightBrowserLauncher} (added in a later task), which calls
 * {@code playwright.chromium().launchPersistentContext(userDataDir, options)} — that call
 * returns a {@link BrowserContext} directly (the persistent-context API never goes through
 * {@code com.microsoft.playwright.Browser}). Tests supply a fake so the lifecycle logic in
 * {@link BrowserSession} can be verified without a real Chromium.
 *
 * <p>The returned {@link BrowserContext} owns the Chromium process tree; closing it
 * terminates all renderer/GPU/utility children.
 */
@FunctionalInterface
public interface BrowserLauncher {

    /**
     * Launch a persistent context using the supplied Playwright instance and return it.
     *
     * @param playwright     the Playwright instance (created by the caller, {@link BrowserSession})
     * @param userDataDir    persistent profile directory (cookies/login survive restarts)
     * @param executablePath custom browser path, or {@code null} to use Playwright's bundled Chromium
     * @param headless       whether to run headless
     * @return the live persistent {@link BrowserContext}
     */
    BrowserContext launch(Playwright playwright, Path userDataDir, String executablePath, boolean headless);
}
