package {{javaPackage}};

import fan.summer.fengyu.sdk.JsonRpcWorker;
import fan.summer.fengyu.sdk.RpcContext;
import {{javaPackage}}.contract.{{javaClassPrefix}}Contract.HelloInput;
import {{javaPackage}}.contract.{{javaClassPrefix}}Contract.HelloOutput;

/**
 * Registers {{pluginName}}'s JSON-RPC handlers on a fresh {@link JsonRpcWorker}.
 *
 * Shared by both entry points so the production worker (stdin/stdout via
 * {@link {{javaClassPrefix}}WorkerMain}) and the IDE-debug worker (loopback TCP via
 * {@code PluginDevMain} in {@code src/test/java}) run <strong>exactly the same</strong> handlers.
 *
 * <p>Handlers use the records declared in {@code {{javaClassPrefix}}Contract}. Replace the
 * {@code hello} demo by editing that contract and this registration; the toolchain extracts
 * schemas from Java during every generate/check/build.
 */
public final class {{javaClassPrefix}}Worker {
    private {{javaClassPrefix}}Worker() {}

    public static JsonRpcWorker create() {
        return new JsonRpcWorker().method(
            "hello",
            HelloInput.class,
            HelloOutput.class,
            (HelloInput input, RpcContext ctx) -> new HelloOutput("Hello, " + input.name()));
    }
}
