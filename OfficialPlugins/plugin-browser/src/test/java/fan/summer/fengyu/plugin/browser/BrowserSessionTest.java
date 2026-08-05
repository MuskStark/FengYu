package fan.summer.fengyu.plugin.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit-tests {@link BrowserSession}'s lazy-start and idempotent-close behaviour using a
 * fake {@link BrowserLauncher} that returns Mockito mocks of the Playwright types — so the
 * lifecycle can be verified without launching a real Chromium or the bundled Node driver.
 *
 * <p>Adapted to the corrected persistent-context design: the launcher returns a
 * {@link BrowserContext} (not a {@code Browser}), matching
 * {@code playwright.chromium().launchPersistentContext(...)}.
 *
 * <p>{@link BrowserSession#createPlaywright()} is overridden per-test to return a mock
 * {@link Playwright}, avoiding the real {@link Playwright#create()} which would spawn the
 * Node driver subprocess.
 */
class BrowserSessionTest {

    private static final Path TEST_PROFILE =
            Path.of(System.getProperty("java.io.tmpdir"), "fengyu-browser-test-profile");

    /** Builds a session whose Playwright instance is a mock, so no subprocess is spawned. */
    private static BrowserSession newSession(BrowserContext context, Playwright playwright,
                                             int[] launchCount) {
        return new BrowserSession(
                TEST_PROFILE,
                null,   // executablePath = use bundled
                false,  // headless
                (pw, dir, exe, headless) -> {
                    if (launchCount != null) launchCount[0]++;
                    return context;
                }) {
            @Override
            protected Playwright createPlaywright() {
                return playwright;
            }
        };
    }

    @Test
    void ensureStartedLaunchesLazilyOnce() {
        BrowserContext context = mock(BrowserContext.class);
        Page page = mock(Page.class);
        when(context.pages()).thenReturn(List.of(page));
        Playwright playwright = mock(Playwright.class);

        int[] launchCount = {0};
        BrowserSession session = newSession(context, playwright, launchCount);

        assertFalse(session.isRunning(), "not running before ensureStarted");
        assertNull(session.context(), "context null before start");

        session.ensureStarted();
        assertEquals(1, launchCount[0], "launcher invoked exactly once on first ensureStarted");

        session.ensureStarted();
        assertEquals(1, launchCount[0], "launcher NOT invoked again on second ensureStarted");

        assertTrue(session.isRunning(), "running after start");
        assertSame(context, session.context(), "context is live after start");
        assertSame(page, session.page(), "page is the context's existing page");
    }

    @Test
    void pageLazilyStartsAndCreatesPageWhenContextHasNone() {
        BrowserContext context = mock(BrowserContext.class);
        Page createdPage = mock(Page.class);
        when(context.pages()).thenReturn(new ArrayList<>());   // empty -> session must newPage()
        when(context.newPage()).thenReturn(createdPage);
        Playwright playwright = mock(Playwright.class);

        BrowserSession session = newSession(context, playwright, null);

        Page p = session.page();   // triggers ensureStarted()
        assertSame(createdPage, p, "page() returns the page created on an empty context");
        verify(context, times(1)).newPage();
        assertTrue(session.isRunning());
    }

    @Test
    void closeIsIdempotentAndClosesContext() {
        BrowserContext context = mock(BrowserContext.class);
        Page page = mock(Page.class);
        when(context.pages()).thenReturn(List.of(page));
        Playwright playwright = mock(Playwright.class);

        BrowserSession session = newSession(context, playwright, null);

        session.ensureStarted();
        session.close();
        session.close();   // must not throw and must not double-close

        verify(context, times(1)).close();
        verify(page, times(1)).close();
        verify(playwright, times(1)).close();
        assertFalse(session.isRunning(), "not running after close");
        assertNull(session.context(), "context nulled after close");
    }
}
