package fan.summer.fengyu.plugin.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Owns the Playwright lifecycle for the plugin-browser worker process: one
 * {@link Playwright} instance, one persistent {@link BrowserContext} (launched via
 * {@code playwright.chromium().launchPersistentContext(...)}), and one {@link Page}.
 * Lazily started on first use and reused across tool calls so the AI's sequential
 * calls (navigate → click → type) share one browsing session.
 *
 * <p><b>Persistent-context design.</b> {@code launchPersistentContext} returns a
 * {@link BrowserContext} directly — it never goes through
 * {@code com.microsoft.playwright.Browser}. The {@link BrowserContext} owns the
 * Chromium process tree; closing it terminates all renderer/GPU/utility children.
 * Closing the {@link Playwright} instance terminates the bundled Node driver
 * subprocess.
 *
 * <p>Methods are {@code synchronized} for safety; the JSON-RPC dispatch in
 * {@code JsonRpcWorker} is single-threaded per frame in practice, so only one
 * handler runs at a time, but the guard keeps callers honest.
 *
 * <p>Not {@code final}: {@link #createPlaywright()} is a protected seam tests override to
 * substitute a mock {@link Playwright} (the real {@link Playwright#create()} spawns the
 * bundled Node driver subprocess).
 */
public class BrowserSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BrowserSession.class);

    private final Path userDataDir;
    private final String executablePath;
    private final boolean headless;
    private final BrowserLauncher launcher;

    private Playwright playwright;
    private BrowserContext context;
    private Page page;

    /**
     * @param userDataDir    persistent profile directory (cookies/login survive restarts); not null
     * @param executablePath custom browser path, or {@code null} to use Playwright's bundled Chromium
     * @param headless       whether to run headless
     * @param launcher       seam that starts Playwright's persistent context; not null
     */
    public BrowserSession(Path userDataDir, String executablePath, boolean headless,
                          BrowserLauncher launcher) {
        this.userDataDir = Objects.requireNonNull(userDataDir, "userDataDir");
        this.executablePath = executablePath;
        this.headless = headless;
        this.launcher = Objects.requireNonNull(launcher, "launcher");
    }

    /**
     * Starts the browser if not already running. Idempotent: the launcher is invoked at most once.
     *
     * <p>Creates the Playwright instance, launches the persistent context (which spawns the
     * Chromium process tree), and grabs the context's first page — creating one if the context
     * started empty.
     */
    public synchronized void ensureStarted() {
        if (context != null) return;
        log.info("Starting browser (userDataDir={}, headless={}, executablePath={})",
                userDataDir, headless, executablePath);
        if (!userDataDir.toFile().exists() && !userDataDir.toFile().mkdirs()) {
            throw new IllegalStateException("Could not create userDataDir: " + userDataDir);
        }
        playwright = createPlaywright();
        context = launcher.launch(playwright, userDataDir, executablePath, headless);
        page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
    }

    /**
     * Creates the Playwright instance. Overridable so tests can return a mock instead of
     * spawning the real bundled Node driver subprocess.
     */
    protected Playwright createPlaywright() {
        return Playwright.create();
    }

    /** The live persistent context, or {@code null} before {@link #ensureStarted()}. */
    public synchronized BrowserContext context() {
        return context;
    }

    /**
     * The current page; starts the browser lazily if needed.
     */
    public synchronized Page page() {
        ensureStarted();
        return page;
    }

    /** True if the browser is currently running. */
    public synchronized boolean isRunning() {
        return context != null;
    }

    /**
     * Closes the page, the persistent context (terminates the Chromium process tree), and
     * Playwright (terminates the bundled Node driver subprocess). Idempotent; each step is
     * guarded independently so one failure does not skip the others.
     */
    @Override
    public synchronized void close() {
        Page p = page;
        page = null;
        BrowserContext ctx = context;
        context = null;
        Playwright pw = playwright;
        playwright = null;
        try {
            if (p != null) p.close();
        } catch (Exception e) {
            log.warn("page close failed: {}", e.getMessage());
        }
        try {
            if (ctx != null) ctx.close();
        } catch (Exception e) {
            log.warn("browser context close failed: {}", e.getMessage());
        }
        try {
            if (pw != null) pw.close();
        } catch (Exception e) {
            log.warn("playwright close failed: {}", e.getMessage());
        }
    }
}
