package fan.summer.fengyu.plugin.browser;

import fan.summer.fengyu.sdk.JsonRpcWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Entry point for the plugin-browser worker. Resolves the browser executable, creates a singleton
 * {@link BrowserSession}, wires nine {@code browser_*} JSON-RPC handlers onto a
 * {@link JsonRpcWorker}, and runs the stdio loop. A JVM shutdown hook ensures the browser is reaped
 * even on abnormal exit (SIGTERM, host crash that closes stdin, etc.).
 *
 * <p><b>Configuration (environment variables).</b>
 * <ul>
 *   <li>{@code FENGYU_PLUGIN_DATA_DIR} — root data dir for the plugin (profile, screenshots,
 *       downloaded Chromium). Defaults to {@code ~/.fengyu/plugins/fan.summer.browser}.</li>
 *   <li>{@code FENGYU_BROWSER_HEADLESS} — {@code true}/{@code false}; defaults to {@code false}
 *       (headed) so a human can watch the AI drive a visible browser.</li>
 * </ul>
 *
 * <p><b>Shutdown hook.</b> {@link BrowserSession#close()} is idempotent and safe to call from a
 * shutdown thread, so registering it via {@code Runtime.addShutdownHook} is enough to guarantee the
 * Chromium process tree and the bundled Node driver are terminated when the JVM exits — even when
 * the worker is killed before {@code run()} returns cleanly.
 */
public final class BrowserWorkerMain {

    private static final Logger log = LoggerFactory.getLogger(BrowserWorkerMain.class);

    /** Default data dir when {@code FENGYU_PLUGIN_DATA_DIR} is unset. */
    static final String DEFAULT_DATA_DIR =
            System.getProperty("user.home") + "/.fengyu/plugins/fan.summer.browser";

    private BrowserWorkerMain() {}

    /**
     * Production entry point: resolve env, build the session + handlers, register the worker, and
     * run the stdio JSON-RPC loop until stdin closes or the parent-death watchdog fires.
     *
     * @param args ignored
     */
    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(System.getenv().getOrDefault("FENGYU_PLUGIN_DATA_DIR", DEFAULT_DATA_DIR));
        boolean headless = Boolean.parseBoolean(
                System.getenv().getOrDefault("FENGYU_BROWSER_HEADLESS", "false"));

        // User-configured executable path is wired through plugin settings in a later task; for now
        // the resolver consults the on-disk cache and auto-downloads on first use.
        ChromiumResolver resolver = ChromiumResolver.forEnvironment(dataDir, () -> null);
        String executablePath = resolver.resolve();
        log.info("Resolved browser executablePath={}", executablePath);

        BrowserSession session = new BrowserSession(
                dataDir.resolve("profile"), executablePath, headless, new PlaywrightBrowserLauncher());
        Runtime.getRuntime().addShutdownHook(new Thread(session::close, "browser-shutdown"));

        BrowserHandlers handlers = new BrowserHandlers(session, dataDir.resolve("screenshots"));
        worker(handlers).run();
    }

    /**
     * Build a {@link JsonRpcWorker} with all nine {@code browser_*} handlers registered, each
     * wrapped by {@link BrowserHandlers#handle(String, fan.summer.fengyu.sdk.PluginHandler)} for
     * uniform {@code {success, summary, ...}} envelopes and entry/exit logging.
     *
     * <p>Method names are the public API and must match Task 6's manifest {@code method} fields
     * exactly: {@code browser_navigate/click/type/get_text/query/screenshot/wait_for/eval_js/close}.
     */
    static JsonRpcWorker worker(BrowserHandlers h) {
        JsonRpcWorker w = new JsonRpcWorker();
        w.on("browser_navigate",   h.handle("browser_navigate",   h::navigate));
        w.on("browser_click",      h.handle("browser_click",      h::click));
        w.on("browser_type",       h.handle("browser_type",       h::type));
        w.on("browser_get_text",   h.handle("browser_get_text",   h::getText));
        w.on("browser_query",      h.handle("browser_query",      h::query));
        w.on("browser_screenshot", h.handle("browser_screenshot", h::screenshot));
        w.on("browser_wait_for",   h.handle("browser_wait_for",   h::waitFor));
        w.on("browser_eval_js",    h.handle("browser_eval_js",    h::evalJs));
        w.on("browser_close",      h.handle("browser_close",      h::close));
        return w;
    }

    /**
     * Test seam: build the worker with a no-op {@link BrowserSession} so the registration test does
     * not need a real browser. The launcher throws {@link UnsupportedOperationException} — it is
     * never invoked because no tool is dispatched in the registration test (the test only asserts
     * the nine method names are registered, via the SDK's duplicate-rejection behaviour).
     */
    static JsonRpcWorker workerForTest() {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        BrowserSession session = new BrowserSession(
                tmp.resolve("fb-browser-worker-test"),
                null, true,
                (playwright, userDataDir, executablePath, headless) -> {
                    throw new UnsupportedOperationException("test launcher; no browser should start");
                });
        BrowserHandlers h = new BrowserHandlers(session, tmp);
        return worker(h);
    }
}
