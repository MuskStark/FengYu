package fan.summer.fengyu.plugin.markdown;

import fan.summer.fengyu.sdk.JsonRpcWorker;

/**
 * Markdown editor worker. Speaks newline-delimited JSON-RPC 2.0 on stdio. The single
 * {@code render} method renders Markdown to HTML via commonmark. {@link JsonRpcWorker#run()}
 * redirects stdout to stderr so the protocol stream on stdout stays clean.
 */
public final class MarkdownWorkerMain {
    private MarkdownWorkerMain() {}

    public static void main(String[] args) throws Exception {
        MarkdownRpcHandlers handlers = new MarkdownRpcHandlers();
        worker(handlers).run();
    }

    static JsonRpcWorker worker(MarkdownRpcHandlers handlers) {
        return new JsonRpcWorker().on("render", handlers.handle("render", handlers::render));
    }
}
