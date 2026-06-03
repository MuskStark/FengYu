package fan.summer.buildintool.browser;

import com.microsoft.playwright.*;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.util.Base64;

/**
 * Manages a Playwright browser session — one headed Chromium instance with a single page.
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
     * Launches a headed Chromium browser and opens a new page.
     */
    public BrowserSession() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
            .setHeadless(false)
            .setArgs(java.util.List.of("--start-maximized")));
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
            .setViewportSize(null));
        page = context.newPage();
        log.info("Browser session started (headed Chromium)");
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
     */
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
}
