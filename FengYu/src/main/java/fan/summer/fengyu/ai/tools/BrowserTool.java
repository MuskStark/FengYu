package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host-side AI tool that delegates browser operations to the Electron desktop shell over
 * a loopback HTTP bridge. Only registered when {@code fengyu.desktop=true} (set by the
 * Electron sidecar at JVM spawn). In web mode the bean is absent and {@code browser_*}
 * tools never appear in the AI catalog.
 *
 * <p>The 9 {@code @Tool} method names and the {@code {success, summary, ...}} return
 * envelope mirror the former {@code plugin-browser} worker verbatim so prompts/skills
 * are unaffected. Text capping ({@link #TEXT_CAP}) and sample limiting
 * ({@link #SAMPLE_LIMIT}) are applied here (Electron returns raw values).
 *
 * <p>Approval-gated via {@link ApprovalRequiredTool} — same gate as {@code execute_command}.
 */
@Component
@ConditionalOnProperty("fengyu.desktop")
public class BrowserTool implements ApprovalRequiredTool {

    public static final int TEXT_CAP = 64_000;
    public static final int SAMPLE_LIMIT = 5;
    private static final String TRUNCATION_MARKER = "…[truncated]";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BrowserBridgeClient client;

    /** Spring constructor: reads bridge address from env. */
    public BrowserTool() {
        this(BrowserBridgeClient.fromEnv());
    }

    /** Test/injection constructor. */
    BrowserTool(BrowserBridgeClient client) {
        this.client = client;
    }

    // ── 9 AI tools ────────────────────────────────────────────────────────────

    @Tool(name = "browser_navigate",
          description = "Navigate the browser to a URL. Returns the final URL and page title.")
    public String navigate(
            @ToolParam(description = "Absolute http(s) URL to open.") String url,
            @ToolParam(required = false,
                       description = "Wait condition: load, domcontentloaded, or networkidle (default load).")
            String waitUntil) {
        return bridge("browser_navigate", params("url", url, "waitUntil", waitUntil), 60);
    }

    @Tool(name = "browser_click",
          description = "Click an element matching the CSS selector.")
    public String click(
            @ToolParam(description = "CSS selector of the element to click.") String selector) {
        return bridge("browser_click", params("selector", selector), 30);
    }

    @Tool(name = "browser_type",
          description = "Type text into an element matching the selector, optionally clearing it first.")
    public String type(
            @ToolParam(description = "CSS selector of the input element.") String selector,
            @ToolParam(description = "Text to type.") String text,
            @ToolParam(required = false, description = "Clear the field first (default true).") Boolean clear) {
        return bridge("browser_type", params("selector", selector, "text", text, "clear", clear), 30);
    }

    @Tool(name = "browser_get_text",
          description = "Read visible text of the page or a single element. Capped at " + TEXT_CAP + " chars.")
    public String getText(
            @ToolParam(required = false, description = "CSS selector; defaults to whole page body.") String selector) {
        String result = bridge("browser_get_text", params("selector", selector), 30);
        return capText(result);
    }

    @Tool(name = "browser_query",
          description = "Count elements matching a selector and return up to " + SAMPLE_LIMIT + " innerText samples.")
    public String query(
            @ToolParam(description = "CSS selector.") String selector) {
        String result = bridge("browser_query", params("selector", selector), 30);
        return limitSamples(result);
    }

    @Tool(name = "browser_screenshot",
          description = "Capture a PNG screenshot. Returns the saved file path, dimensions, and an accessibility tree (YAML) the model reads instead of the image.")
    public String screenshot(
            @ToolParam(required = false, description = "Capture the full scrollable page (default false).") Boolean fullPage,
            @ToolParam(required = false, description = "CSS selector to capture a single element.") String selector) {
        return bridge("browser_screenshot", params("fullPage", fullPage, "selector", selector), 30);
    }

    @Tool(name = "browser_wait_for",
          description = "Wait until an element reaches a state (attached, detached, visible, hidden).")
    public String waitFor(
            @ToolParam(description = "CSS selector.") String selector,
            @ToolParam(required = false,
                       description = "State to wait for: attached, detached, visible, hidden (default visible).")
            String state,
            @ToolParam(required = false, description = "Timeout in seconds (default 30, max 600).") Integer timeout) {
        return bridge("browser_wait_for", params("selector", selector, "state", state, "timeout", timeout), 40);
    }

    @Tool(name = "browser_eval_js",
          description = "Evaluate a JavaScript expression in the page and return its stringified value.")
    public String evalJs(
            @ToolParam(description = "JavaScript expression to evaluate.") String script) {
        return bridge("browser_eval_js", params("script", script), 30);
    }

    @Tool(name = "browser_close", description = "Close the browser window.")
    public String close() {
        return bridge("browser_close", Map.of(), 15);
    }

    // ── bridge dispatch + envelope shaping ───────────────────────────────────

    /** Subclass seam for tests (in-memory stub). */
    protected Map<String, Object> invokeBridge(String method, Map<String, Object> params, int timeoutSeconds) {
        return client.invoke(method, params, timeoutSeconds);
    }

    /** Sends to the bridge and serializes the envelope to a JSON string. */
    private String bridge(String method, Map<String, Object> params, int timeoutSeconds) {
        try {
            Map<String, Object> envelope = invokeBridge(method, params, timeoutSeconds);
            return JSON.writeValueAsString(envelope);
        } catch (BrowserBridgeUnavailableException e) {
            return failure("browser bridge unavailable");
        } catch (Exception e) {
            return failure("browser tool failed: " + safeMsg(e));
        }
    }

    private static String failure(String summary) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("success", false);
        e.put("summary", summary.replaceAll("[\\r\\n]", " "));
        return toJson(e);
    }

    private static String toJson(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"summary\":\"failed to serialize browser result\"}";
        }
    }

    private static String safeMsg(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    @SuppressWarnings("unchecked")
    private static String capText(String json) {
        try {
            Map<String, Object> e = JSON.readValue(json, Map.class);
            Object o = e.get("text");
            if (o instanceof String s && s.length() > TEXT_CAP) {
                e.put("text", s.substring(0, TEXT_CAP - TRUNCATION_MARKER.length()) + TRUNCATION_MARKER);
            }
            return toJson(e);
        } catch (Exception ex) {
            return json;
        }
    }

    @SuppressWarnings("unchecked")
    private static String limitSamples(String json) {
        try {
            Map<String, Object> e = JSON.readValue(json, Map.class);
            Object o = e.get("samples");
            if (o instanceof List<?> list && list.size() > SAMPLE_LIMIT) {
                e.put("samples", new ArrayList<>(list.subList(0, SAMPLE_LIMIT)));
            }
            return toJson(e);
        } catch (Exception ex) {
            return json;
        }
    }

    /** Builds a params map, dropping keys whose value is null. */
    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
