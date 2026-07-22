package fan.summer.fengyu.plugin.markdown;

import fan.summer.fengyu.sdk.JsonRpcWorker;
import fan.summer.fengyu.sdk.PluginHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Adapts {@link MarkdownPlugin} to the official SDK JSON-RPC handler contract. Each result
 * follows the {success, summary, ...} envelope; failures become {success:false, summary}.
 */
public final class MarkdownRpcHandlers {
    private static final Logger log = LoggerFactory.getLogger(MarkdownRpcHandlers.class);
    private final MarkdownPlugin plugin = new MarkdownPlugin();

    public Object render(Map<String, Object> params) {
        return result(() -> plugin.invoke("render", params));
    }

    /** Wraps a handler so every result follows the {success, summary, ...} contract. */
    public PluginHandler safe(PluginHandler handler) {
        return params -> {
            try { return cast(handler.handle(params)); }
            catch (Exception error) {
                log.warn("Markdown plugin handler failed", error);
                return failure(safeMessage(error));
            }
        };
    }

    private Map<String, Object> result(Supplier<Object> operation) {
        try {
            Object value = operation.get();
            return value instanceof Map<?, ?> map ? cast(map) : ok("ok", null, value);
        } catch (Exception error) {
            log.warn("Markdown plugin operation failed", error);
            return failure(safeMessage(error));
        }
    }

    private static Map<String, Object> ok(String summary, String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("summary", summary);
        if (key != null) result.put(key, value);
        return result;
    }

    private static Map<String, Object> failure(String summary) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("summary", summary == null || summary.isBlank() ? "Markdown operation failed" : summary);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new IllegalArgumentException("Handler returned an invalid result");
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return "Markdown operation failed";
        return message.replace('\r', ' ').replace('\n', ' ');
    }
}
