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
 * <p>The {@code @Tool} method names and the {@code {success, summary, ...}} return
 * envelope mirror the former {@code plugin-browser} worker (plus {@code browser_find} for
 * element refs) so prompts/skills are unaffected. Text capping ({@link #TEXT_CAP}) and sample limiting
 * ({@link #SAMPLE_LIMIT}) are applied here (Electron returns raw values).
 *
 * <p>Approval-gated via {@link ApprovalRequiredTool} — same gate as {@code execute_command}.
 */
@Component
@ConditionalOnProperty("fengyu.desktop")
public class BrowserTool implements ApprovalRequiredTool, ToolEffectProvider {

    public static final int TEXT_CAP = 64_000;
    public static final int SAMPLE_LIMIT = 5;
    private static final String TRUNCATION_MARKER = "…[truncated]";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BrowserBridgeClient client;
    private final BrowserSession session;

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
        this(client, new BrowserSession());
    }

    BrowserTool(BrowserBridgeClient client, BrowserSession session) {
        this.client = client;
        this.session = session;
    }

    @Override
    public ToolEffect effectFor(String toolName) {
        return switch (toolName) {
            case "browser_find", "browser_snapshot", "browser_get_text", "browser_query",
                    "browser_screenshot", "browser_wait_for", "browser_tabs",
                    "browser_contexts" -> ToolEffect.READ;
            case "browser_navigate", "browser_click", "browser_type", "browser_press",
                    "browser_history", "browser_hover", "browser_scroll", "browser_select",
                    "browser_eval_js", "browser_close", "browser_new_tab", "browser_select_tab",
                    "browser_close_tab", "browser_new_context", "browser_select_context",
                    "browser_close_context", "browser_batch" -> ToolEffect.EXTERNAL;
            default -> ToolEffect.EXTERNAL;
        };
    }

    // ── AI tools ──────────────────────────────────────────────────────────────

    @Tool(name = "browser_navigate",
          description = "Navigate the browser to a URL. Returns the final URL and page title.")
    public String navigate(
            @ToolParam(description = "Absolute http(s) URL to open.") String url,
            @ToolParam(required = false,
                       description = "Wait condition: load, domcontentloaded, or networkidle (default load).")
            String waitUntil) {
        return bridge("browser_navigate", params("url", url, "waitUntil", waitUntil), 60);
    }

    @Tool(name = "browser_history",
          description = "Navigate the active tab's history: back, forward, or reload. Returns the resulting URL and title, and fails clearly when no back/forward entry exists.")
    public String history(
            @ToolParam(description = "History action: back, forward, or reload.") String action) {
        return bridge("browser_history", params("action", action), 30);
    }

    @Tool(name = "browser_find",
          description = "Auto-wait for an element located by CSS selector and return a ref id for later click/type/get_text calls. The ref keeps targeting that exact DOM node until navigation or node replacement; a stale ref fails instead of silently targeting another element.")
    public String find(
            @ToolParam(description = "CSS selector of the element.") String selector,
            @ToolParam(required = false,
                       description = "1-based index when the selector matches several elements. If omitted and the selector matches more than one, the call fails with a hint to pass nth or refine the selector.")
            Integer nth) {
        return bridge("browser_find", params("selector", selector, "nth", nth), 30);
    }

    @Tool(name = "browser_snapshot",
          description = "Inspect the current page like Codex domSnapshot. Returns URL, title, visible text, and only rendered interactive elements with stable [ref] ids. Call this immediately after navigation and when page state changes; prefer its refs for click/type/press instead of guessing CSS or using eval_js.")
    public String snapshot() {
        return bridge("browser_snapshot", Map.of(), 30);
    }

    @Tool(name = "browser_contexts",
          description = "List isolated browser contexts in this logical session, including active context and tab counts. Contexts do not share cookies or local storage.")
    public String contexts() {
        return bridge("browser_contexts", Map.of(), 15);
    }

    @Tool(name = "browser_new_context",
          description = "Create and select an isolated browser context with its own cookies/local storage and a new main tab.")
    public String newContext() {
        return bridge("browser_new_context", Map.of(), 15);
    }

    @Tool(name = "browser_select_context",
          description = "Select an existing isolated browser context and restore its active tab and cached refs.")
    public String selectContext(
            @ToolParam(description = "Context id returned by browser_contexts/browser_new_context.") String contextId) {
        return bridge("browser_select_context", params("contextId", contextId), 15);
    }

    @Tool(name = "browser_close_context",
          description = "Close every tab in a browser context. Defaults to the current context; another context becomes active.")
    public String closeContext(
            @ToolParam(required = false, description = "Context id; defaults to current context.") String contextId) {
        return bridge("browser_close_context", params("contextId", contextId), 15);
    }

    @Tool(name = "browser_tabs",
          description = "List tabs in the current isolated browser context, including each tab id, URL, title, and which tab is active.")
    public String tabs() {
        return bridge("browser_tabs", Map.of(), 15);
    }

    @Tool(name = "browser_new_tab",
          description = "Open and select a new tab in the current isolated browser context. Optionally navigate it to an absolute http(s) URL.")
    public String newTab(
            @ToolParam(required = false, description = "Optional absolute http(s) URL to open.") String url) {
        return bridge("browser_new_tab", params("url", url), 60);
    }

    @Tool(name = "browser_select_tab",
          description = "Select an existing browser tab by id. Its cached URL/title and element refs become current again.")
    public String selectTab(@ToolParam(description = "Tab id returned by browser_tabs/browser_new_tab.") String tabId) {
        return bridge("browser_select_tab", params("tabId", tabId), 15);
    }

    @Tool(name = "browser_close_tab",
          description = "Close a browser tab by id, or the current tab when omitted. Another remaining tab becomes active.")
    public String closeTab(
            @ToolParam(required = false, description = "Tab id to close; defaults to the current tab.") String tabId) {
        return bridge("browser_close_tab", params("tabId", tabId), 15);
    }

    @Tool(name = "browser_click",
          description = "Click an element with real CDP pointer input. Auto-waits until the target is visible, stable, enabled, in the viewport, and not covered. Pass either a CSS selector or a ref from browser_find (ref wins).")
    public String click(
            @ToolParam(required = false, description = "CSS selector of the element to click.") String selector,
            @ToolParam(required = false, description = "1-based index of the match when the selector matches several.") Integer nth,
            @ToolParam(required = false, description = "Ref id returned by browser_find; takes precedence over selector.") String ref) {
        return bridge("browser_click", params("selector", selector, "nth", nth, "ref", ref), 30);
    }

    @Tool(name = "browser_hover",
          description = "Hover an element with real CDP pointer input. Auto-waits until the target is visible, stable, in the viewport, and not covered; useful for menus, tooltips, and hover-only controls.")
    public String hover(
            @ToolParam(required = false, description = "CSS selector of the element to hover.") String selector,
            @ToolParam(required = false, description = "1-based index of the match when the selector matches several.") Integer nth,
            @ToolParam(required = false, description = "Ref id returned by browser_snapshot/browser_find; takes precedence over selector.") String ref) {
        return bridge("browser_hover", params("selector", selector, "nth", nth, "ref", ref), 30);
    }

    @Tool(name = "browser_scroll",
          description = "Scroll the active page with a real CDP mouse-wheel event. Positive deltaY scrolls down, negative scrolls up; deltaX scrolls horizontally. Optionally target a selector/ref so nested scroll areas receive the event.")
    public String scroll(
            @ToolParam(required = false, description = "Horizontal CSS-pixel delta; default 0, clamped to ±10000.") Integer deltaX,
            @ToolParam(required = false, description = "Vertical CSS-pixel delta; default 600, clamped to ±10000.") Integer deltaY,
            @ToolParam(required = false, description = "Optional CSS selector whose visible area should receive the wheel event.") String selector,
            @ToolParam(required = false, description = "1-based index of the match when the selector matches several.") Integer nth,
            @ToolParam(required = false, description = "Optional ref from browser_snapshot/browser_find; takes precedence over selector.") String ref) {
        return bridge("browser_scroll", params("deltaX", deltaX, "deltaY", deltaY,
                "selector", selector, "nth", nth, "ref", ref), 30);
    }

    @Tool(name = "browser_type",
          description = "Type text into an input with real pointer and keyboard/CDP input. Auto-waits for an actionable editable target, clears with Select All + Backspace by default, and verifies that the text persisted. Pass either a CSS selector or a ref from browser_find (ref wins).")
    public String type(
            @ToolParam(required = false, description = "CSS selector of the input element.") String selector,
            @ToolParam(description = "Text to type.") String text,
            @ToolParam(required = false, description = "Clear the field first (default true).") Boolean clear,
            @ToolParam(required = false, description = "1-based index of the match when the selector matches several.") Integer nth,
            @ToolParam(required = false, description = "Ref id returned by browser_find; takes precedence over selector.") String ref) {
        return bridge("browser_type", params("selector", selector, "text", text, "clear", clear, "nth", nth, "ref", ref), 30);
    }

    @Tool(name = "browser_press",
          description = "Press a keyboard key on an actionable input using real CDP keyboard events. Use refs from browser_snapshot. Supports Enter, Tab, Escape, Backspace, Delete, Space, arrows, Home/End/PageUp/PageDown, letters/digits, and modifiers such as ControlOrMeta+Enter.")
    public String press(
            @ToolParam(required = false, description = "CSS selector of the target element.") String selector,
            @ToolParam(description = "Key or shortcut to press, for example Enter or ControlOrMeta+A.") String key,
            @ToolParam(required = false, description = "1-based index when the selector matches several.") Integer nth,
            @ToolParam(required = false, description = "Ref id returned by browser_snapshot/browser_find; takes precedence over selector.") String ref) {
        return bridge("browser_press", params("selector", selector, "key", key, "nth", nth, "ref", ref), 30);
    }

    @Tool(name = "browser_select",
          description = "Select one option in a native HTML <select>. Matches the exact option value first, then its visible label, dispatches input/change events, and verifies the selected value.")
    public String select(
            @ToolParam(required = false, description = "CSS selector of the native select element.") String selector,
            @ToolParam(description = "Exact option value or visible label to select.") String option,
            @ToolParam(required = false, description = "1-based index of the select when the selector matches several.") Integer nth,
            @ToolParam(required = false, description = "Ref id returned by browser_snapshot/browser_find; takes precedence over selector.") String ref) {
        return bridge("browser_select", params("selector", selector, "option", option,
                "nth", nth, "ref", ref), 30);
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
          description = "Capture a PNG screenshot and return dimensions, URL/title, accessibility tree, and an actionable DOM snapshot with stable refs. Use those refs for the next click/type/press. Pass a selector/ref to capture a single element.")
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

    @Tool(name = "browser_batch",
          description = "Capture one DOM snapshot and immediately perform one click, type, or key press in the same serialized bridge request. Use this when the target selector/ref is already known and a fresh snapshot plus action should be atomic.")
    public String batch(
            @ToolParam(description = "Action after the snapshot: click, type, or press.") String action,
            @ToolParam(required = false, description = "CSS selector for the action target.") String selector,
            @ToolParam(required = false, description = "1-based selector match index.") Integer nth,
            @ToolParam(required = false, description = "Previously cached ref; takes precedence over selector.") String ref,
            @ToolParam(required = false, description = "Text for action=type.") String text,
            @ToolParam(required = false, description = "Key or shortcut for action=press.") String key,
            @ToolParam(required = false, description = "Clear before action=type (default true).") Boolean clear) {
        return bridge("browser_batch", params("action", action, "selector", selector, "nth", nth,
                "ref", ref, "text", text, "key", key, "clear", clear), 45);
    }

    @Tool(name = "browser_eval_js",
          description = "Last-resort diagnostic: evaluate JavaScript in the page. Do not use it to discover controls, fill fields, click, submit, read URL/title, or verify navigation; use browser_snapshot and ref-based actions instead.")
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
            String validation = session.validate(method, params);
            if (validation != null) return failure(validation);
            Map<String, Object> envelope = invokeBridge(method, session.route(params), timeoutSeconds);
            session.observe(method, envelope);
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
