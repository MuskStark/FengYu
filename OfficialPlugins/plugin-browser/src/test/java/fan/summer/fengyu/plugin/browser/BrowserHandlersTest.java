package fan.summer.fengyu.plugin.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit-tests {@link BrowserHandlers}'s nine {@code browser_*} JSON-RPC handlers. Each test stubs
 * the {@link BrowserSession}/{@link Page} seam with Mockito mocks (no real Chromium is launched)
 * and asserts the {@code {success, summary, ...}} envelope shape and that the expected Playwright
 * call was invoked.
 *
 * <p>Adapted to the real Playwright Java 1.49.0 API discovered from the dependency on the classpath:
 * <ul>
 *   <li>There is no {@code page.evalJs(...)}; the equivalent is {@code page.evaluate(String)}.</li>
 *   <li>There is no {@code page.accessibility()} in 1.49.0; the accessibility tree is taken from
 *       {@code page.locator("body").ariaSnapshot()}, which returns a YAML string.</li>
 *   <li>Body text is read via {@code page.locator("body").innerText()} (no {@code innerText(null)}
 *       overload exists).</li>
 * </ul>
 */
class BrowserHandlersTest {

    @TempDir
    Path tempDir;

    private BrowserSession session;
    private Page page;
    private BrowserHandlers handlers;

    @BeforeEach
    void setUp() {
        page = mock(Page.class);
        session = mock(BrowserSession.class);
        when(session.page()).thenReturn(page);
        handlers = new BrowserHandlers(session, tempDir.resolve("shots"));
    }

    // ---- navigate -------------------------------------------------------

    @Test
    void navigateReturnsUrlAndTitle() {
        when(page.title()).thenReturn("Example");
        Map<String, Object> out = handlers.navigate(Map.of("url", "https://example.com"));
        assertEquals(true, out.get("success"));
        assertEquals("https://example.com", out.get("url"));
        assertEquals("Example", out.get("title"));
        // either the no-arg or options navigate(...) may be called; both are acceptable.
        verify(page).navigate(eq("https://example.com"));
    }

    @Test
    void navigateRejectsBlankUrl() {
        Map<String, Object> out = handlers.navigate(Map.of("url", "  "));
        assertEquals(false, out.get("success"));
        assertTrue(((String) out.get("summary")).toLowerCase().contains("url"),
                () -> "summary should mention url, was: " + out.get("summary"));
    }

    // ---- click ----------------------------------------------------------

    @Test
    void clickInvokesPageClick() {
        Map<String, Object> out = handlers.click(Map.of("selector", "#go"));
        assertEquals(true, out.get("success"));
        assertEquals(true, out.get("clicked"));
        verify(page).click("#go");
    }

    @Test
    void clickRejectsBlankSelector() {
        Map<String, Object> out = handlers.click(Map.of("selector", ""));
        assertEquals(false, out.get("success"));
        assertTrue(((String) out.get("summary")).toLowerCase().contains("selector"));
    }

    // ---- type -----------------------------------------------------------

    @Test
    void typeClearsThenFills() {
        Map<String, Object> out = handlers.type(Map.of("selector", "#q", "text", "hello"));
        assertEquals(true, out.get("success"));
        assertEquals(true, out.get("filled"));
        verify(page).fill("#q", "hello");
    }

    @Test
    void typeRejectsBlankSelector() {
        Map<String, Object> out = handlers.type(Map.of("text", "hello"));
        assertEquals(false, out.get("success"));
        assertTrue(((String) out.get("summary")).toLowerCase().contains("selector"));
    }

    // ---- getText --------------------------------------------------------

    @Test
    void getTextReturnsTextAndLength() {
        Locator body = mock(Locator.class);
        when(page.locator("body")).thenReturn(body);
        when(body.innerText()).thenReturn("hello world");
        Map<String, Object> out = handlers.getText(Map.of());
        assertEquals(true, out.get("success"));
        assertEquals("hello world", out.get("text"));
        assertEquals(11, out.get("length"));
    }

    @Test
    void getTextHonoursSelector() {
        Locator target = mock(Locator.class);
        when(page.locator("#greeting")).thenReturn(target);
        when(target.innerText()).thenReturn("hi");
        Map<String, Object> out = handlers.getText(Map.of("selector", "#greeting"));
        assertEquals(true, out.get("success"));
        assertEquals("hi", out.get("text"));
        assertEquals(2, out.get("length"));
    }

    @Test
    void getTextTruncatesBeyondCap() {
        String big = "x".repeat(70_000);
        Locator body = mock(Locator.class);
        when(page.locator("body")).thenReturn(body);
        when(body.innerText()).thenReturn(big);
        Map<String, Object> out = handlers.getText(Map.of());
        assertEquals(true, out.get("success"));
        String text = (String) out.get("text");
        // truncation marker keeps the response to TEXT_CAP (64_000) characters.
        assertTrue(text.length() <= BrowserHandlers.TEXT_CAP,
                () -> "truncated text must not exceed TEXT_CAP, was " + text.length());
        assertTrue(text.length() < big.length(), "text should have been truncated");
        assertEquals(text.length(), out.get("length"));
    }

    // ---- query ----------------------------------------------------------

    @Test
    void queryReturnsCountAndSamples() {
        ElementHandle h1 = mock(ElementHandle.class);
        ElementHandle h2 = mock(ElementHandle.class);
        when(h1.innerText()).thenReturn("first");
        when(h2.innerText()).thenReturn("second");
        when(page.querySelectorAll("a")).thenReturn(List.of(h1, h2));
        Map<String, Object> out = handlers.query(Map.of("selector", "a"));
        assertEquals(true, out.get("success"));
        assertEquals(2, out.get("count"));
        assertInstanceOf(List.class, out.get("samples"));
        assertEquals(List.of("first", "second"), out.get("samples"));
    }

    @Test
    void queryCapsSamplesAtFive() {
        List<ElementHandle> handles = java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> {
                    ElementHandle h = mock(ElementHandle.class);
                    when(h.innerText()).thenReturn("n" + i);
                    return h;
                })
                .toList();
        when(page.querySelectorAll("div")).thenReturn(handles);
        Map<String, Object> out = handlers.query(Map.of("selector", "div"));
        assertEquals(true, out.get("success"));
        assertEquals(12, out.get("count"));
        @SuppressWarnings("unchecked")
        List<String> samples = (List<String>) out.get("samples");
        assertEquals(5, samples.size(), "samples must be capped at SAMPLE_LIMIT");
    }

    @Test
    void queryRejectsBlankSelector() {
        Map<String, Object> out = handlers.query(Map.of());
        assertEquals(false, out.get("success"));
    }

    // ---- screenshot -----------------------------------------------------

    @Test
    void screenshotSavesPngAndReportsDimensionsAndA11y() {
        // A minimal 1x1 transparent PNG.
        byte[] png = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, // PNG signature
            // IHDR chunk (length=13, type=IHDR, width=1, height=1, bit depth, color type, etc.)
            0x00, 0x00, 0x00, 0x13, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, // width=1, height=1
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4, (byte) 0x89,
            // IDAT chunk + IEND omitted; dimension parser only reads IHDR.
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE, 0x42, 0x60, (byte) 0x82
        };
        when(page.screenshot(any())).thenReturn(png);
        Locator body = mock(Locator.class);
        when(page.locator("body")).thenReturn(body);
        when(body.ariaSnapshot()).thenReturn("- generic");

        Map<String, Object> out = handlers.screenshot(Map.of());
        assertEquals(true, out.get("success"), () -> "summary=" + out.get("summary"));
        assertEquals(1, out.get("width"));
        assertEquals(1, out.get("height"));
        assertEquals("- generic", out.get("a11yTree"));
        String imagePath = (String) out.get("imagePath");
        assertNotNull(imagePath);
        assertTrue(imagePath.endsWith(".png"), () -> "imagePath should be a .png: " + imagePath);
        assertTrue(new java.io.File(imagePath).exists(), "PNG should have been written to disk");
        verify(page).screenshot(any());
    }

    // ---- waitFor --------------------------------------------------------

    @Test
    void waitForReturnsOk() {
        when(page.waitForSelector(eq("#done"), any())).thenReturn(mock(ElementHandle.class));
        Map<String, Object> out = handlers.waitFor(Map.of("selector", "#done"));
        assertEquals(true, out.get("success"));
        assertEquals(true, out.get("ok"));
        verify(page).waitForSelector(eq("#done"), any());
    }

    @Test
    void waitForRejectsBlankSelector() {
        Map<String, Object> out = handlers.waitFor(Map.of());
        assertEquals(false, out.get("success"));
    }

    // ---- evalJs ---------------------------------------------------------

    @Test
    void evalJsReturnsStringValue() {
        when(page.evaluate("1+1")).thenReturn(42);
        Map<String, Object> out = handlers.evalJs(Map.of("script", "1+1"));
        assertEquals(true, out.get("success"));
        assertEquals("42", out.get("value"));
        verify(page).evaluate("1+1");
    }

    @Test
    void evalJsRejectsBlankScript() {
        Map<String, Object> out = handlers.evalJs(Map.of("script", "   "));
        assertEquals(false, out.get("success"));
        assertTrue(((String) out.get("summary")).toLowerCase().contains("script"));
    }

    // ---- close ----------------------------------------------------------

    @Test
    void closeShutsSession() {
        Map<String, Object> out = handlers.close(Map.of());
        assertEquals(true, out.get("success"));
        assertEquals(true, out.get("closed"));
        verify(session).close();
    }

    // ---- constructor side effects --------------------------------------

    @Test
    void constructorCreatesScreenshotDirParent() {
        Path nested = tempDir.resolve("a/b/c/shots");
        assertFalse(nested.toFile().exists());
        new BrowserHandlers(session, nested);
        assertTrue(nested.toFile().exists(),
                "constructor must create the screenshotDir (incl. parents)");
    }
}
