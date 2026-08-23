package fan.summer.fengyu.plugin.markdown;

import fan.summer.fengyu.sdk.PluginMessages;
import fan.summer.fengyu.sdk.RpcContext;
import fan.summer.markdown.contract.MarkdownContract.RenderInput;
import fan.summer.markdown.contract.MarkdownContract.RenderOutput;

/**
 * Typed RPC handlers for the Markdown worker. The single {@code render} method renders Markdown
 * source to sanitized HTML via {@link MarkdownPlugin} and assembles a {@link RenderOutput}. The
 * worker wires it up through the typed {@code worker.method(PluginMethods.RENDER, ...)} registration
 * in {@link MarkdownWorkerMain}, which deserializes {@link RenderInput} and serializes the returned
 * {@link RenderOutput}.
 *
 * <p>Cancellation is cooperative: after the render the handler checks
 * {@link RpcContext#cancellation()} so a {@code $/cancelRequest} that lands mid-render yields a
 * clean {@code CANCELLED} response instead of running on. Logging goes through
 * {@link RpcContext#logger()} and never records the raw markdown input — only the character count
 * and elapsed time.
 */
public final class MarkdownRpcHandlers {

    private final MarkdownPlugin plugin;

    public MarkdownRpcHandlers() {
        this.plugin = new MarkdownPlugin(PluginMessages.forClassLoader(
                PluginMessages.DEFAULT_BASE_NAME, MarkdownRpcHandlers.class));
    }

    /**
     * Render {@code markdown} to HTML.
     *
     * @param input the rendered markdown source
     * @param ctx   the per-call context (cancellation token + logger)
     * @return a {@code RenderOutput} with {@code success=true}, a localized summary, and the HTML
     */
    public RenderOutput render(RenderInput input, RpcContext ctx) {
        String source = input.markdown() == null ? "" : input.markdown();
        int inputLength = source.length();
        long started = System.nanoTime();
        String html = plugin.renderHtml(source);
        // Cooperative checkpoint: honour a cancel that arrived during the render.
        ctx.cancellation().throwIfCancelled();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        ctx.logger().info("rendered {} chars to HTML in {} ms", inputLength, elapsedMs);
        return new RenderOutput(html, true, plugin.summary(inputLength));
    }
}
