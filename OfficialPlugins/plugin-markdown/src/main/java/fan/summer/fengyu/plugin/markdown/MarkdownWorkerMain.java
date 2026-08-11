package fan.summer.fengyu.plugin.markdown;

import fan.summer.fengyu.sdk.JsonRpcWorker;
import fan.summer.fengyu.sdk.RpcContext;
import fan.summer.markdown.generated.PluginMethods;
import fan.summer.markdown.generated.RenderInput;
import fan.summer.markdown.generated.RenderOutput;

/**
 * Markdown editor worker. Speaks newline-delimited JSON-RPC 2.0 on stdio. The single {@code render}
 * method is registered through the typed {@link JsonRpcWorker#method} API: the SDK deserializes the
 * incoming params into {@link RenderInput}, binds an {@link RpcContext} to the handler thread, and
 * serializes the returned {@link RenderOutput} back into the response. {@link JsonRpcWorker#run()}
 * redirects stdout to stderr so the protocol stream on stdout stays clean.
 */
public final class MarkdownWorkerMain {
    private MarkdownWorkerMain() {}

    public static void main(String[] args) throws Exception {
        MarkdownRpcHandlers handlers = new MarkdownRpcHandlers();
        worker(handlers).run();
    }

    static JsonRpcWorker worker(MarkdownRpcHandlers handlers) {
        return new JsonRpcWorker().method(
                PluginMethods.RENDER, RenderInput.class, RenderOutput.class,
                (RenderInput input, RpcContext ctx) -> handlers.render(input, ctx));
    }
}
