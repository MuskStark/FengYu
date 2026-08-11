package {{javaPackage}};

import fan.summer.fengyu.sdk.JsonRpcWorker;
import fan.summer.fengyu.sdk.RpcContext;
import {{javaPackage}}.generated.HelloInput;
import {{javaPackage}}.generated.HelloOutput;
import {{javaPackage}}.generated.PluginMethods;

/**
 * Registers {{pluginName}}'s JSON-RPC handlers on a fresh {@link JsonRpcWorker}.
 *
 * Shared by both entry points so the production worker (stdin/stdout via
 * {@link {{javaClassPrefix}}WorkerMain}) and the IDE-debug worker (loopback TCP via
 * {@code PluginDevMain} in {@code src/test/java}) run <strong>exactly the same</strong> handlers.
 *
 * <p>Handlers are registered with the typed {@code method(...)} API against the generated
 * {@code PluginMethods} constants and {@code HelloInput}/{@code HelloOutput} records (produced by
 * {@code fengyu build|dev|init} from {@code manifest.json}'s {@code rpc.methods}). Replace the
 * {@code hello} demo with your own methods by editing {@code manifest.json} and re-running
 * {@code fengyu build} (or {@code fengyu dev}).
 */
public final class {{javaClassPrefix}}Worker {
    private {{javaClassPrefix}}Worker() {}

    public static JsonRpcWorker create() {
        return new JsonRpcWorker().method(
            PluginMethods.HELLO,
            HelloInput.class,
            HelloOutput.class,
            (HelloInput input, RpcContext ctx) -> new HelloOutput("Hello, " + input.name()));
    }
}
