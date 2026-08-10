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
 * <p>The 10 {@code @Tool} method names and the {@code {success, summary, ...}} return
 * envelope mirror the former {@code plugin-browser} worker (plus {@code browser_find} for
 * element refs) so prompts/skills are unaffected. Text capping ({@link #TEXT_CAP}) and sample limiting
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

    /**
     * Spring constructor: reads the bridge address from env. When the bridge env
     * ({@code FENGYU_BROWSER_BRIDGE_PORT/TOKEN}) is absent — e.g. the backend is started from
     * an IDE without the Electron shell — the bean still registers (so {@code fengyu.desktop=true}
     * alone surfaces the {@code browser_*} tools) but enters a degraded mode: every call returns a
     * {@code "browser bridge unavailable"} envelope instead of throwing at construction time.
     */
    public BrowserTool() {
        this(safeFromEnv());
    }

    private static BrowserBridgeClient safeFromEnv() {
        try {
            return BrowserBridgeClient.fromEnv();
        } catch (IllegalStateException envMissing) {
            return null;  // degraded mode — see invokeBridge null-check
        }
    }

    /** Test/injection constructor. */
    BrowserTool(BrowserBridgeClient client) {
        this.client = client;
    }

    // ── 10 AI tools ───────────────────────────────────────────────────────────

    @Tool(name = "browser_navigate",
          description = "Navigate the browser to a URL. Returns the final URL and page title.")
    public String navigate(
            @ToolParam(description = "Absolute http(s) URL to open.") String url,
            @ToolParam(required = false,
                       description = "Wait condition: load, domcontentloaded, or networkidle (default load).")
            String waitUntil) {
        return bridge("browser_navigate", params("url", url, "waitUntil", waitUntil), 60);
    }

    @Tool(name = "browser_find",
          description = "Locate an element by CSS selector and return a stable ref id for use in later click/type/get_text calls. Stamps a data attribute on the matched node so the same element is targeted across re-renders.")
    public String find(
            @ToolParam(description = "CSS selector of the element.") String selector,
            @ToolParam(required = false,
                       description = "1-based index when the selector matches several elements. If omitted and the selector matches more than one, the call fails with a hint to pass nth or refine the selector.")
            Integer nth) {
        return bridge("browser_find", params("selector", selector, "nth", nth), 30);
    }

    @Tool(name = "browser_click",
          description = "Click an element. Uses a real CDP mouse press+release at the element centre (not a JS click), so isTrusted-checked buttons and mousedown listeners fire. Pass either a CSS selector or a ref from browser_find (ref wins).")
    public String click(
            @ToolParam(required = false, description = "CSS selector of the element to click.") String selector,
            @ToolParam(required = false, description = "1-based index of the match when the selector matches several.") Integer nth,
            @ToolParam(required = false, description = "Ref id returned by browser_find; takes precedence over selector.") String ref) {
        return bridge("browser_click", params("selector", selector, "nth", nth, "ref", ref), 30);
    }

    @Tool(name = "browser_type",
          description = "Type text into an input element. Uses CDP Input.insertText (the browser's real text-edit pipeline), so React/Vue controlled components update their state correctly — unlike direct value assignment. Optionally clears first. Pass either a CSS selector or a ref from browser_find (ref wins).")
    public String type(
            @ToolParam(required = false, description = "CSS selector of the input element.") String selector,
            @ToolParam(description = "Text to type.") String text,
            @ToolParam(required = false, description = "Clear the field first (default true).") Boolean clear,
            @ToolParam(required = false, description = "1-based index of the match when the selector matches several.") Integer nth,
            @ToolParam(required = false, description = "Ref id returned by browser_find; takes precedence over selector.") String ref) {
        return bridge("browser_type", params("selector", selector, "text", text, "clear", clear, "nth", nth, "ref", ref), 30);
    }

    @Tool(name = "browser_get_text",
          description = "Read visible text of the page or a single element. Capped at " + TEXT_CAP + " chars. Pass a selector/ref to scope to one element.")
    public String getText(
            @ToolParam(required = false, description = "CSS selector; defaults to whole page body.") String selector,
            @ToolParam(required = false, description = "1-based index of the match when the selector matches several.") Integer nth,
            @ToolParam(required = false, description = "Ref id returned by browser_find; takes precedence over selector.") String ref) {
        String result = bridge("browser_get_text", params("selector", selector, "nth", nth, "ref", ref), 30);
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
          description = "Capture a PNG screenshot. Returns the saved file path, dimensions, and an accessibility tree (YAML) the model reads instead of the image. Pass a selector/ref to capture a single element.")
    public String screenshot(
            @ToolParam(required = false, description = "Capture the full scrollable page (default false).") Boolean fullPage,
            @ToolParam(required = false, description = "CSS selector to capture a single element.") String selector,
            @ToolParam(required = false, description = "1-based index of the match when the selector matches several.") Integer nth,
            @ToolParam(required = false, description = "Ref id returned by browser_find; takes precedence over selector.") String ref) {
        return bridge("browser_screenshot", params("fullPage", fullPage, "selector", selector, "nth", nth, "ref", ref), 30);
    }

    @Tool(name = "browser_wait_for",
          description = "Wait until an element reaches a state (attached, detached, visible, hidden).")
    public String waitFor(
            @ToolParam(required = false, description = "CSS selector.") String selector,
            @ToolParam(required = false,
                       description = "State to wait for: attached, detached, visible, hidden (default visible).")
            String state,
            @ToolParam(required = false, description = "Timeout in seconds (default 30, max 600).") Integer timeout,
            @ToolParam(required = false, description = "1-based index of the match when the selector matches several.") Integer nth,
            @ToolParam(required = false, description = "Ref id returned by browser_find; takes precedence over selector.") String ref) {
        return bridge("browser_wait_for", params("selector", selector, "state", state, "timeout", timeout, "nth", nth, "ref", ref), 40);
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
        if (client == null) {
            throw new BrowserBridgeUnavailableException("browser bridge unavailable (no Electron shell)");
        }
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
