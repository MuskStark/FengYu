package fan.summer.buildintool.browser;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Manages a Playwright browser session using the system's already-installed
 * Chrome or Edge browser — no separate browser download required.
 *
 * <p>On construction, the session detects the system browser (Chrome, then Edge,
 * then Chromium) and launches it in headed mode via Playwright. The Playwright
 * driver (Node.js wrapper) is bundled in the Maven dependency, so no manual
 * installation is needed at all.</p>
 *
 * <p>All methods are synchronous and blocking. The session must be {@link #close()}'d
 * when done to release the browser process.</p>
 */
public class BrowserSession implements AutoCloseable {

    private static final PluginLogger log = LoggerFactory.getLogger(BrowserSession.class);

    private Playwright playwright;
    private Browser browser;
    private Page page;

    /**
     * Launches a headed browser and opens a new page.
     * Uses the system's Chrome/Edge/Chromium — no browser download required.
     *
     * @throws RuntimeException if no supported browser is found on the system
     */
    public BrowserSession() {
        Path browserPath = detectSystemBrowser();
        if (browserPath == null) {
            throw new RuntimeException(
                "No supported browser found. Please install Google Chrome, Microsoft Edge, or Chromium.");
        }
        log.info("Using system browser: {}", browserPath);

        // Explicitly skip any browser download — only use the system's installed browser
        Playwright.CreateOptions createOptions = new Playwright.CreateOptions()
            .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1"));

        playwright = Playwright.create(createOptions);
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
            .setHeadless(false)
            .setExecutablePath(browserPath)
            .setArgs(List.of("--start-maximized")));
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
            .setViewportSize(null));
        page = context.newPage();
        log.info("Browser session started (headed, system browser)");
    }

    /**
     * Returns the Playwright Page for direct access.
     */
    public Page page() {
        return page;
    }

    /**
     * Navigates to the given URL.
     *
     * @return the response description
     */
    public String navigate(String url) {
        log.debug("navigate: {}", url);
        Response response = page.navigate(url);
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        return response != null ? "Navigated to " + url + " (status: " + response.status() + ")" : "Navigation completed";
    }

    /**
     * Clicks an element matching the CSS selector.
     */
    public String click(String selector) {
        log.debug("click: {}", selector);
        try {
            page.click(selector, new Page.ClickOptions().setTimeout(5000));
            return "Clicked element: " + selector;
        } catch (PlaywrightException e) {
            return "Click failed for '" + selector + "': " + e.getMessage();
        }
    }

    /**
     * Clears the field matching the selector and types text into it.
     */
    public String type(String selector, String text) {
        log.debug("type: {} => [{} chars]", selector, text.length());
        try {
            page.fill(selector, "", new Page.FillOptions().setTimeout(5000));
            page.fill(selector, text);
            return "Typed text into: " + selector;
        } catch (PlaywrightException e) {
            return "Type failed for '" + selector + "': " + e.getMessage();
        }
    }

    /**
     * Presses a keyboard key.
     */
    public String press(String key) {
        log.debug("press: {}", key);
        page.keyboard().press(key);
        return "Pressed key: " + key;
    }

    /**
     * Scrolls the page.
     *
     * @param direction "up" or "down"
     * @param amount    number of scroll increments (each ~300px)
     */
    public String scroll(String direction, int amount) {
        log.debug("scroll: {} x{}", direction, amount);
        int delta = "up".equalsIgnoreCase(direction) ? -300 : 300;
        for (int i = 0; i < amount; i++) {
            page.mouse().wheel(0, delta);
        }
        return "Scrolled " + direction + " x" + amount;
    }

    /**
     * Extracts text content from the page.
     *
     * @param selector CSS selector, or null to extract full page text
     * @return the extracted text content
     */
    public String extract(String selector) {
        if (selector != null && !selector.isBlank()) {
            log.debug("extract: {}", selector);
            try {
                ElementHandle element = page.querySelector(selector);
                if (element == null) return "Element not found: " + selector;
                String text = element.textContent();
                return text != null ? text : "(empty text content)";
            } catch (PlaywrightException e) {
                return "Extract failed for '" + selector + "': " + e.getMessage();
            }
        } else {
            log.debug("extract: full page");
            String text = page.textContent("body");
            if (text == null) return "(page body has no text)";
            // Truncate if too large for LLM context
            if (text.length() > 8000) {
                return text.substring(0, 8000) + "\n... [truncated, total " + text.length() + " chars]";
            }
            return text;
        }
    }

    /**
     * Takes a screenshot and returns it as a base64-encoded PNG string.
     */
    public String screenshot() {
        log.debug("screenshot");
        byte[] bytes = page.screenshot(new Page.ScreenshotOptions().setFullPage(false));
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Waits for the specified number of seconds.
     * This is an intentional wait action for browser automation, not a polling loop.
     */
    @SuppressWarnings("BusyWait")
    public String wait(double seconds) {
        log.debug("wait: {}s", seconds);
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "Waited " + seconds + " seconds";
    }

    /**
     * Returns the current page URL.
     */
    public String getCurrentUrl() {
        return page.url();
    }

    /**
     * Returns the current page title.
     */
    public String getTitle() {
        return page.title();
    }

    /**
     * Closes the browser and Playwright instance.
     */
    @Override
    public void close() {
        try {
            if (browser != null) browser.close();
        } catch (Exception e) {
            log.warn("Error closing browser: {}", e.getMessage());
        }
        try {
            if (playwright != null) playwright.close();
        } catch (Exception e) {
            log.warn("Error closing Playwright: {}", e.getMessage());
        }
        browser = null;
        playwright = null;
        page = null;
        log.info("Browser session closed");
    }

    // ── System browser detection ────────────────────────────────

    private static final String OS_NAME = System.getProperty("os.name", "").toLowerCase();

    /**
     * Detects the system's installed browser.
     * Checks Chrome, then Edge, then Chromium.
     *
     * @return the browser executable path, or null if none found
     */
    private static Path detectSystemBrowser() {

        List<Path> candidates;
        if (OS_NAME.contains("mac")) {
            candidates = List.of(
                Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"),
                Path.of("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"),
                Path.of("/Applications/Chromium.app/Contents/MacOS/Chromium")
            );
        } else if (OS_NAME.contains("win")) {
            candidates = List.of(
                Path.of(System.getenv("PROGRAMFILES") != null
                    ? System.getenv("PROGRAMFILES") + "\\Google\\Chrome\\Application\\chrome.exe"
                    : "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"),
                Path.of(System.getenv("PROGRAMFILES(X86)") != null
                    ? System.getenv("PROGRAMFILES(X86)") + "\\Google\\Chrome\\Application\\chrome.exe"
                    : "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe"),
                Path.of(System.getenv("PROGRAMFILES") != null
                    ? System.getenv("PROGRAMFILES") + "\\Microsoft\\Edge\\Application\\msedge.exe"
                    : "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe"),
                Path.of(System.getenv("LOCALAPPDATA") != null
                    ? System.getenv("LOCALAPPDATA") + "\\Google\\Chrome\\Application\\chrome.exe"
                    : "C:\\Users\\Default\\AppData\\Local\\Google\\Chrome\\Application\\chrome.exe")
            );
        } else {
            // Linux
            candidates = List.of(
                Path.of("/usr/bin/google-chrome"),
                Path.of("/usr/bin/google-chrome-stable"),
                Path.of("/usr/bin/chromium-browser"),
                Path.of("/usr/bin/chromium"),
                Path.of("/usr/bin/microsoft-edge"),
                Path.of("/usr/bin/microsoft-edge-stable")
            );
        }

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
