package fan.summer.fengyu.ai.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java-side state for one logical browser automation session.
 *
 * <p>The Electron bridge remains authoritative for live windows and DOM nodes. This cache gives
 * the host enough state to route every operation to the same context/tab, reject unknown refs
 * before an unsafe action crosses the bridge, and retain the latest URL/title without forcing a
 * second snapshot. Ref sets are isolated per tab and cleared after successful navigation.</p>
 */
public final class BrowserSession {

    public static final String DEFAULT_CONTEXT = "default";
    public static final String DEFAULT_TAB = "main";

    private static final Pattern SNAPSHOT_REF = Pattern.compile("\\[([A-Za-z0-9_-]{1,160})]");
    private static final Set<String> REF_ACTIONS = Set.of(
            "browser_click", "browser_type", "browser_press", "browser_get_text",
            "browser_screenshot", "browser_wait_for", "browser_batch");

    private final String id;
    private final Map<String, TabState> tabs = new LinkedHashMap<>();
    private String currentContextId = DEFAULT_CONTEXT;
    private String currentTabId = DEFAULT_TAB;

    public BrowserSession() {
        this("fy-browser-" + UUID.randomUUID());
    }

    BrowserSession(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("session id must not be blank");
        this.id = id;
        tabs.put(key(DEFAULT_CONTEXT, DEFAULT_TAB), new TabState());
    }

    public synchronized String id() {
        return id;
    }

    public synchronized String currentContextId() {
        return currentContextId;
    }

    public synchronized String currentTabId() {
        return currentTabId;
    }

    public synchronized String currentUrl() {
        return current().url;
    }

    public synchronized String currentTitle() {
        return current().title;
    }

    public synchronized Set<String> currentRefs() {
        return Set.copyOf(current().refs);
    }

    /** Add private routing fields understood by the Electron bridge. */
    synchronized Map<String, Object> route(Map<String, Object> params) {
        Map<String, Object> routed = new LinkedHashMap<>();
        if (params != null) routed.putAll(params);
        routed.put("_sessionId", id);
        routed.put("_contextId", currentContextId);
        routed.put("_tabId", currentTabId);
        return routed;
    }

    /** Returns a model-facing error when an action references a ref absent from this tab. */
    synchronized String validate(String method, Map<String, Object> params) {
        if (!REF_ACTIONS.contains(method) || params == null) return null;
        Object value = params.get("ref");
        if (!(value instanceof String ref) || ref.isBlank()) return null;
        return current().refs.contains(ref)
                ? null
                : "unknown or stale browser ref '" + ref + "' for tab " + currentTabId
                    + "; call browser_snapshot or browser_find again";
    }

    /** Fold a successful bridge envelope into the per-tab cache. */
    synchronized void observe(String method, Map<String, Object> envelope) {
        if (envelope == null || !Boolean.TRUE.equals(envelope.get("success"))) return;
        String context = string(envelope.get("contextId"));
        String tab = string(envelope.get("tabId"));
        if (context != null) currentContextId = context;
        if (tab != null) currentTabId = tab;
        TabState state = current();

        String url = string(envelope.get("url"));
        String title = string(envelope.get("title"));
        if (url != null) state.url = url;
        if (title != null) state.title = title;

        if ("browser_navigate".equals(method)) state.refs.clear();
        if ("browser_close".equals(method)) {
            state.refs.clear();
            state.url = "";
            state.title = "";
        }
        if ("browser_close_tab".equals(method)) {
            String closed = string(envelope.get("closedTabId"));
            if (closed != null) tabs.remove(key(currentContextId, closed));
            current();
        }
        if ("browser_close_context".equals(method)) {
            String closed = string(envelope.get("closedContextId"));
            if (closed != null) tabs.keySet().removeIf(item -> item.startsWith(closed + '\u0000'));
            current();
        }
        if ("browser_select_tab".equals(method) || "browser_new_tab".equals(method)
                || "browser_select_context".equals(method) || "browser_new_context".equals(method)) current();

        collectRef(envelope.get("ref"), state.refs);
        collectSnapshotRefs(envelope.get("snapshot"), state.refs);
        collectSnapshotRefs(envelope.get("domSnapshot"), state.refs);
        collectNestedResults(envelope.get("results"), state);
    }

    private static void collectNestedResults(Object value, TabState state) {
        if (!(value instanceof List<?> results)) return;
        for (Object item : results) {
            if (!(item instanceof Map<?, ?> map)) continue;
            collectRef(map.get("ref"), state.refs);
            collectSnapshotRefs(map.get("snapshot"), state.refs);
            collectSnapshotRefs(map.get("domSnapshot"), state.refs);
        }
    }

    private static void collectRef(Object value, Set<String> refs) {
        String ref = string(value);
        if (ref != null && SNAPSHOT_REF.matcher("[" + ref + "]").matches()) refs.add(ref);
    }

    private static void collectSnapshotRefs(Object value, Set<String> refs) {
        if (!(value instanceof String snapshot) || snapshot.isBlank()) return;
        Matcher matcher = SNAPSHOT_REF.matcher(snapshot);
        while (matcher.find()) refs.add(matcher.group(1));
    }

    private TabState current() {
        return tabs.computeIfAbsent(key(currentContextId, currentTabId), ignored -> new TabState());
    }

    private static String key(String contextId, String tabId) {
        return contextId + '\u0000' + tabId;
    }

    private static String string(Object value) {
        if (!(value instanceof String text) || text.isBlank()) return null;
        return text;
    }

    /** Diagnostic snapshot used by unit tests and future session UIs. */
    synchronized List<TabSnapshot> tabs() {
        List<TabSnapshot> result = new ArrayList<>();
        for (Map.Entry<String, TabState> entry : tabs.entrySet()) {
            int split = entry.getKey().indexOf('\u0000');
            result.add(new TabSnapshot(entry.getKey().substring(0, split),
                    entry.getKey().substring(split + 1), entry.getValue().url,
                    entry.getValue().title, Set.copyOf(entry.getValue().refs)));
        }
        return List.copyOf(result);
    }

    record TabSnapshot(String contextId, String tabId, String url, String title, Set<String> refs) {}

    private static final class TabState {
        private String url = "";
        private String title = "";
        private final Set<String> refs = new LinkedHashSet<>();
    }
}
