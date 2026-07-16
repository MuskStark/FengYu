package {{javaPackage}};

import fan.summer.fengyu.sdk.JsonRpcWorker;

import java.util.Map;

/**
 * {{pluginName}} worker entry point.
 *
 * Registers JSON-RPC methods on the worker loop and blocks reading requests
 * from stdin. The {@code hello} method echoes a greeting back to the UI.
 */
public final class {{javaClassPrefix}}WorkerMain {
    public static void main(String[] args) throws Exception {
        new JsonRpcWorker()
            .on("hello", params -> Map.of("message", "Hello, " + JsonRpcWorker.string(params, "name")))
            .run();
    }
}
