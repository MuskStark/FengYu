package {{javaPackage}};

import fan.summer.fengyu.sdk.JsonRpcWorker;

import java.util.Map;

/**
 * Registers {{pluginName}}'s JSON-RPC handlers on a fresh {@link JsonRpcWorker}.
 *
 * Shared by both entry points so the production worker (stdin/stdout via
 * {@link {{javaClassPrefix}}WorkerMain}) and the IDE-debug worker (loopback TCP via
 * {@code PluginDevMain} in {@code src/test/java}) run <strong>exactly the same</strong> handlers.
 *
 * <p>Add new methods here with {@code worker.on("method", params -> ...)}. The {@code hello}
 * method is a demo that echoes a greeting back to the UI — replace or extend it.
 */
public final class {{javaClassPrefix}}Worker {
    private {{javaClassPrefix}}Worker() {}

    public static JsonRpcWorker create() {
        return new JsonRpcWorker()
            .on("hello", params -> Map.of("message", "Hello, " + JsonRpcWorker.string(params, "name")));
    }
}
