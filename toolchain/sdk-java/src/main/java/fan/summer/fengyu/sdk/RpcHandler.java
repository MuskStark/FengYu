package fan.summer.fengyu.sdk;

/**
 * Typed RPC handler registered via
 * {@code JsonRpcWorker#method(PluginMethods.X, XInput.class, XOutput.class, (input, ctx) -> ...)}.
 * The worker deserializes the JSON-RPC {@code params} into {@code Input} (via Gson) and binds an
 * {@link RpcContext} to the handler thread before invoking {@link #handle}; the returned
 * {@code Output} is serialized back into the response envelope.
 *
 * @param <Input>  the generated input record type (or {@code Map} for untyped handlers)
 * @param <Output> the generated output record type (or {@code Object}/{@code Map})
 * @since 1.4.0
 */
@FunctionalInterface
public interface RpcHandler<Input, Output> {
    Output handle(Input input, RpcContext context) throws Exception;
}
