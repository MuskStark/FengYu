package fan.summer.fengyu.plugin.browser;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** Verifies the module compiles and core dependencies are on the classpath. */
class BrowserScaffoldTest {

    @Test
    void mainClassIsLoadable() {
        assertDoesNotThrow(() -> Class.forName("fan.summer.fengyu.plugin.browser.BrowserWorkerMain"));
    }

    @Test
    void playwrightIsOnClasspath() {
        assertDoesNotThrow(() -> Class.forName("com.microsoft.playwright.Playwright"));
    }

    @Test
    void sdkIsOnClasspath() {
        assertDoesNotThrow(() -> Class.forName("fan.summer.fengyu.sdk.JsonRpcWorker"));
    }
}
