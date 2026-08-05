package fan.summer.fengyu.plugin.browser;

import fan.summer.fengyu.sdk.JsonRpcWorker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies that the {@link JsonRpcWorker} built by {@link BrowserWorkerMain#workerForTest()}
 * registers all nine {@code browser_*} JSON-RPC methods.
 *
 * <p>The SDK's {@link JsonRpcWorker#on(String, fan.summer.fengyu.sdk.PluginHandler)} rejects
 * duplicates with {@link IllegalArgumentException} (it uses {@code putIfAbsent} and throws when the
 * prior value is non-null). Re-registering each method name therefore proves it was already
 * registered by {@code workerForTest()} — without needing reflection into the private handler map or
 * a real browser.
 */
class BrowserWorkerMainTest {

    /** The exact nine method names; must match Task 6's manifest {@code method} fields. */
    private static final String[] NINE_METHODS = {
            "browser_navigate",
            "browser_click",
            "browser_type",
            "browser_get_text",
            "browser_query",
            "browser_screenshot",
            "browser_wait_for",
            "browser_eval_js",
            "browser_close",
    };

    @Test
    void registersAllNineMethods() {
        JsonRpcWorker worker = BrowserWorkerMain.workerForTest();
        assertNotNull(worker, "workerForTest() must return a worker");
        for (String name : NINE_METHODS) {
            assertThrows(IllegalArgumentException.class,
                    () -> worker.on(name, params -> null),
                    "expected " + name + " to be already registered");
        }
    }
}
