package fan.summer.fengyu.plugin.browser;

import fan.summer.fengyu.sdk.JsonRpcWorker;

/**
 * Entry point for the plugin-browser worker. Wires the JSON-RPC method handlers
 * (registered in later tasks) onto the {@link JsonRpcWorker} and runs the stdio loop.
 */
public final class BrowserWorkerMain {
    private BrowserWorkerMain() {}

    public static void main(String[] args) throws Exception {
        // Handlers are added in later tasks; this proves the SDK + Playwright deps resolve.
        new JsonRpcWorker().run();
    }
}
