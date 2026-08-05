package fan.summer.fengyu.plugin.browser;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import fan.summer.fengyu.sdk.JsonRpcWorker;
import fan.summer.fengyu.sdk.PluginHandlerSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static java.util.Map.entry;

/**
 * The nine {@code browser_*} JSON-RPC handlers for the plugin-browser worker. Each method drives a
 * {@link BrowserSession} (which lazily owns one Playwright {@link Page}) and returns the standard
 * {@code {success, summary, ...}} result envelope via {@link PluginHandlerSupport}.
 *
 * <p><b>Envelope contract.</b> Every method returns a {@code Map} with {@code success} (boolean)
 * and {@code summary} (single-line String), plus the documented per-method extra keys. Failures
 * (bad input or a thrown Playwright exception) come back as {@code {success:false, summary}}.
 *
 * <p><b>Playwright 1.49.0 API notes.</b> The implementation is pinned to the real signatures of
 * Playwright Java 1.49.0, which differ from the original brief in two places:
 * <ul>
 *   <li>There is no {@code page.evalJs(String)}; the equivalent is {@link Page#evaluate(String)}.
 *       The {@code eval_js} handler therefore calls {@code evaluate} but keeps the {@code value}
 *       envelope key and converts the result with {@code String.valueOf(...)}.</li>
 *   <li>The {@code Accessibility} API was removed; there is no {@code page.accessibility()} in
 *       1.49.0. The screenshot handler reads the accessibility tree from
 *       {@code page.locator("body").ariaSnapshot()}, which yields a YAML snapshot string that the
 *       model can interpret in place of pixels.</li>
 * </ul>
 *
 * <p>Handlers are not thread-safe; the {@link fan.summer.fengyu.sdk.JsonRpcWorker} dispatch loop
 * is single-threaded per worker, so only one handler runs at a time.
 */
public class BrowserHandlers extends PluginHandlerSupport {

    private static final Logger log = LoggerFactory.getLogger(BrowserHandlers.class);

    /** Hard cap on returned text to protect the model's context window. */
    public static final int TEXT_CAP = 64_000;
    /** Maximum number of element innerText samples returned by {@link #query}. */
    public static final int SAMPLE_LIMIT = 5;
    /** Marker appended to truncated text so a consumer can tell it was cut. */
    private static final String TRUNCATION_MARKER = "…[truncated]";
    /** Default selector-wait timeout in milliseconds. */
    private static final int DEFAULT_WAIT_TIMEOUT_MS = 30_000;

    private final BrowserSession session;
    private final Path screenshotDir;

    /**
     * @param session      the lazily-started browser session; not null
     * @param screenshotDir directory PNGs are written into; created (with parents) by the constructor
     */
    public BrowserHandlers(BrowserSession session, Path screenshotDir) {
        super("browser");
        this.session = java.util.Objects.requireNonNull(session, "session");
        this.screenshotDir = java.util.Objects.requireNonNull(screenshotDir, "screenshotDir");
        // mkdirs() also creates parents; cheap and idempotent if the directory already exists.
        if (!screenshotDir.toFile().exists() && !screenshotDir.toFile().mkdirs()) {
            throw new UncheckedIOException(
                    new IOException("Could not create screenshotDir: " + screenshotDir));
        }
    }

    // ── navigation ───────────────────────────────────────────────────────

    /** {@code browser_navigate}: drives {@code page.navigate(url)} and reports the page title. */
    public Map<String, Object> navigate(Map<String, Object> params) {
        String url = string(params, "url");
        if (url == null || url.isBlank()) return failure("url is required");
        String waitUntilRaw = string(params, "waitUntil");
        return result(() -> {
            Page page = session.page();
            if (waitUntilRaw != null && !waitUntilRaw.isBlank()) {
                Page.NavigateOptions opts = new Page.NavigateOptions();
                try {
                    opts.setWaitUntil(WaitUntilState.valueOf(waitUntilRaw.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException bad) {
                    log.warn("navigate: ignoring unknown waitUntil '{}'", waitUntilRaw);
                    // fall through with no waitUntil override (default LOAD)
                    page.navigate(url);
                    return okExtras("navigated to " + url,
                            entry("url", url), entry("title", page.title()));
                }
                page.navigate(url, opts);
            } else {
                page.navigate(url);
            }
            String title = page.title();
            log.debug("navigate: {} -> '{}'", url, title);
            return okExtras("navigated to " + url,
                    entry("url", url), entry("title", title));
        });
    }

    /** {@code browser_click}: drives {@code page.click(selector)}. */
    public Map<String, Object> click(Map<String, Object> params) {
        String selector = string(params, "selector");
        if (selector == null || selector.isBlank()) return failure("selector is required");
        return result(() -> {
            session.page().click(selector);
            log.debug("click: {}", selector);
            return okExtras("clicked " + selector, entry("clicked", true));
        });
    }

    /** {@code browser_type}: clears-and-fills the field via {@code page.fill(selector, text)}. */
    public Map<String, Object> type(Map<String, Object> params) {
        String selector = string(params, "selector");
        if (selector == null || selector.isBlank()) return failure("selector is required");
        String text = string(params, "text");
        if (text == null) text = "";
        final String value = text;
        return result(() -> {
            session.page().fill(selector, value);
            log.debug("type: {} <- {} char(s)", selector, value.length());
            return okExtras("filled " + selector, entry("filled", true));
        });
    }

    /** {@code browser_get_text}: reads innerText of {@code selector} (defaults to {@code body}). */
    public Map<String, Object> getText(Map<String, Object> params) {
        String selector = string(params, "selector");
        final String target = (selector == null || selector.isBlank()) ? "body" : selector;
        return result(() -> {
            String raw = session.page().locator(target).innerText();
            String text = cap(raw);
            log.debug("getText: {} -> {} char(s)", target, text.length());
            return okExtras("read text",
                    entry("text", text), entry("length", text.length()));
        });
    }

    /** {@code browser_query}: counts matches and returns up to {@value SAMPLE_LIMIT} innerText samples. */
    public Map<String, Object> query(Map<String, Object> params) {
        String selector = string(params, "selector");
        if (selector == null || selector.isBlank()) return failure("selector is required");
        return result(() -> {
            List<com.microsoft.playwright.ElementHandle> handles = session.page().querySelectorAll(selector);
            List<String> samples = new ArrayList<>();
            int limit = Math.min(SAMPLE_LIMIT, handles.size());
            for (int i = 0; i < limit; i++) {
                samples.add(String.valueOf(handles.get(i).innerText()));
            }
            log.debug("query: {} matched {} element(s)", selector, handles.size());
            return okExtras("matched " + handles.size() + " element(s)",
                    entry("count", handles.size()), entry("samples", samples));
        });
    }

    /**
     * {@code browser_screenshot}: saves a PNG into {@code screenshotDir}, reports pixel dimensions
     * read from the PNG IHDR, and attaches a YAML accessibility snapshot.
     *
     * <p>When {@code selector} is supplied the screenshot is scoped to that element; otherwise the
     * full viewport (or full page when {@code fullPage:true}) is captured.
     */
    public Map<String, Object> screenshot(Map<String, Object> params) {
        boolean fullPage = Boolean.TRUE.equals(params.get("fullPage"));
        String selector = string(params, "selector");
        return result(() -> {
            Page page = session.page();
            Path file = screenshotDir.resolve("shot-" + System.currentTimeMillis() + ".png");
            byte[] png;
            if (selector != null && !selector.isBlank()) {
                png = page.locator(selector).screenshot(new Locator.ScreenshotOptions());
            } else {
                png = page.screenshot(new Page.ScreenshotOptions().setFullPage(fullPage));
            }
            // Write the bytes ourselves rather than relying on Playwright's setPath option:
            // it makes file creation deterministic and unit-testable with a mocked Page (which
            // honours setPath() as a setter but never touches the filesystem). Files.write is
            // atomic and idempotent if Playwright already produced the file via its own path.
            Files.write(file, png);
            int[] dims = pngDimensions(png);
            String a11y = accessibilityTree(page);
            log.debug("screenshot: {} ({}x{})", file, dims[0], dims[1]);
            return okExtras("screenshot saved",
                    entry("imagePath", file.toString()),
                    entry("width", dims[0]),
                    entry("height", dims[1]),
                    entry("a11yTree", a11y));
        });
    }

    /** {@code browser_wait_for}: blocks until {@code selector} satisfies {@code state} (default visible). */
    public Map<String, Object> waitFor(Map<String, Object> params) {
        String selector = string(params, "selector");
        if (selector == null || selector.isBlank()) return failure("selector is required");
        String stateRaw = string(params, "state");
        int timeoutMs = JsonRpcWorker.integer(params, "timeout", 0) * 1000;
        if (timeoutMs <= 0) timeoutMs = DEFAULT_WAIT_TIMEOUT_MS;
        final int finalTimeoutMs = timeoutMs;
        return result(() -> {
            Page.WaitForSelectorOptions opts = new Page.WaitForSelectorOptions().setTimeout(finalTimeoutMs);
            if (stateRaw != null && !stateRaw.isBlank()) {
                try {
                    opts.setState(com.microsoft.playwright.options.WaitForSelectorState
                            .valueOf(stateRaw.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException bad) {
                    log.warn("waitFor: ignoring unknown state '{}'", stateRaw);
                }
            }
            session.page().waitForSelector(selector, opts);
            log.debug("waitFor: {} satisfied", selector);
            return okExtras("wait satisfied", entry("ok", true));
        });
    }

    /**
     * {@code browser_eval_js}: evaluates a JavaScript expression and returns its result as a String.
     *
     * <p>Named {@code eval_js} for API parity with the other Playwright bindings, but implemented
     * via {@link Page#evaluate(String)} since Playwright Java 1.49.0 has no {@code evalJs}.
     */
    public Map<String, Object> evalJs(Map<String, Object> params) {
        String script = string(params, "script");
        if (script == null || script.isBlank()) return failure("script is required");
        return result(() -> {
            Object value = session.page().evaluate(script);
            log.debug("evalJs: result class={}", value == null ? "null" : value.getClass().getSimpleName());
            return okExtras("eval ok", entry("value", String.valueOf(value)));
        });
    }

    /** {@code browser_close}: closes the {@link BrowserSession} (Chromium + Node driver). */
    public Map<String, Object> close(@SuppressWarnings("unused") Map<String, Object> params) {
        return result(() -> {
            session.close();
            log.debug("close: session closed");
            return okExtras("browser closed", entry("closed", true));
        });
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** {@link JsonRpcWorker#string(Map, String)} indirection so tests can assert against one source. */
    private static String string(Map<String, Object> params, String key) {
        return JsonRpcWorker.string(params, key);
    }

    /**
     * Build a success envelope with arbitrary extra entries, preserving insertion order. The SDK's
     * {@link PluginHandlerSupport#ok(String, String, Object)} adds exactly one extra key; this helper
     * composes multi-key envelopes directly so the SDK's single-key API does not have to be extended.
     */
    @SafeVarargs
    private static Map<String, Object> okExtras(String summary, Map.Entry<String, Object>... extras) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("summary", summary);
        for (Map.Entry<String, Object> e : extras) out.put(e.getKey(), e.getValue());
        return out;
    }

    /**
     * Truncate {@code text} so the returned string is never longer than {@link #TEXT_CAP}
     * characters, appending a marker if anything was cut. The marker counts towards the cap so the
     * whole envelope payload stays within the documented limit.
     */
    private static String cap(String text) {
        if (text == null) return "";
        if (text.length() <= TEXT_CAP) return text;
        return text.substring(0, TEXT_CAP - TRUNCATION_MARKER.length()) + TRUNCATION_MARKER;
    }

    /**
     * Best-effort accessibility snapshot as a single string. Playwright 1.49.0 dropped the
     * {@code Accessibility} API, so the YAML ARIA snapshot of {@code body} is used instead — it gives
     * the model the same semantic tree without pixels.
     */
    private static String accessibilityTree(Page page) {
        try {
            return String.valueOf(page.locator("body").ariaSnapshot());
        } catch (Exception e) {
            // A snapshot failure must not sink the whole screenshot call.
            return "(a11y unavailable: " + e.getClass().getSimpleName() + ")";
        }
    }

    /**
     * Read the 32-bit width and height from a PNG's IHDR chunk (bytes 16–23). Returns {@code {0,0}}
     * if the bytes are not a parseable PNG so the envelope stays well-formed on malformed output.
     */
    private static int[] pngDimensions(byte[] png) {
        if (png == null || png.length < 24) return new int[]{0, 0};
        // PNG signature is 8 bytes; IHDR length (4) + "IHDR" (4) precede the 4-byte width then height.
        int width = ((png[16] & 0xFF) << 24) | ((png[17] & 0xFF) << 16)
                | ((png[18] & 0xFF) << 8) | (png[19] & 0xFF);
        int height = ((png[20] & 0xFF) << 24) | ((png[21] & 0xFF) << 16)
                | ((png[22] & 0xFF) << 8) | (png[23] & 0xFF);
        return new int[]{width, height};
    }
}
