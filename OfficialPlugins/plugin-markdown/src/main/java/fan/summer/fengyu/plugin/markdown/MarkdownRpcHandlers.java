package fan.summer.fengyu.plugin.markdown;

import fan.summer.fengyu.sdk.JsonRpcWorker;
import fan.summer.fengyu.sdk.PluginHandlerSupport;

import java.util.Map;

/**
 * Adapts {@link MarkdownPlugin} to the official SDK JSON-RPC handler contract. Each result
 * follows the {success, summary, ...} envelope; failures become {success:false, summary}.
 *
 * <p>Entry/exit/failure logging and the result envelope are inherited from
 * {@link PluginHandlerSupport}; register handlers via {@code worker.on("render", handlers.handle("render", handlers::render))}.
 */
public final class MarkdownRpcHandlers extends PluginHandlerSupport {

    private final MarkdownPlugin plugin;

    public MarkdownRpcHandlers() {
        super("markdown");
        this.plugin = new MarkdownPlugin(msgs);
    }

    public Object render(Map<String, Object> params) {
        return result(() -> {
            int inputLength = JsonRpcWorker.string(params, "markdown").length();
            long started = System.nanoTime();
            Object value = plugin.invoke("render", params);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            log.info("rendered {} chars to HTML in {} ms", inputLength, elapsedMs);
            return value instanceof Map<?, ?> map ? cast(map) : ok(t("md.ok"), null, value);
        });
    }
}
