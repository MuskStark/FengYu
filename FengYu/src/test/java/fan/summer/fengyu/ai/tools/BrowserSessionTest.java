package fan.summer.fengyu.ai.tools;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BrowserSessionTest {

    @Test
    void snapshotRefsAreCachedAndUnknownRefsFailBeforeBridgeDispatch() {
        BrowserSession session = new BrowserSession("session-1");
        Map<String, Object> routed = session.route(Map.of("selector", "#go"));
        assertEquals("session-1", routed.get("_sessionId"));
        assertEquals("default", routed.get("_contextId"));
        assertEquals("main", routed.get("_tabId"));

        session.observe("browser_snapshot", Map.of(
                "success", true, "url", "https://example.com", "title", "Example",
                "sessionId", "session-1", "contextId", "default", "tabId", "main",
                "snapshot", "[snap_a_1] button \"Go\"\n[snap_a_2] textbox \"Name\""));

        assertEquals("https://example.com", session.currentUrl());
        assertEquals("Example", session.currentTitle());
        assertTrue(session.currentRefs().contains("snap_a_1"));
        assertNull(session.validate("browser_click", Map.of("ref", "snap_a_1")));
        assertTrue(session.validate("browser_click", Map.of("ref", "missing")).contains("stale"));
    }

    @Test
    void navigationClearsOnlyTheCurrentTabsRefs() {
        BrowserSession session = new BrowserSession("session-2");
        session.observe("browser_snapshot", Map.of("success", true,
                "contextId", "default", "tabId", "main", "snapshot", "[main_ref] button"));
        session.observe("browser_new_tab", Map.of("success", true,
                "contextId", "default", "tabId", "tab_1", "snapshot", "[tab_ref] link"));
        assertTrue(session.currentRefs().contains("tab_ref"));

        session.observe("browser_navigate", Map.of("success", true,
                "contextId", "default", "tabId", "tab_1", "url", "https://new.example"));
        assertTrue(session.currentRefs().isEmpty());

        session.observe("browser_select_tab", Map.of("success", true,
                "contextId", "default", "tabId", "main"));
        assertTrue(session.currentRefs().contains("main_ref"));
    }

    @Test
    void historyNavigationClearsRefsAndNewTargetActionsValidateThem() {
        BrowserSession session = new BrowserSession("session-3");
        session.observe("browser_snapshot", Map.of("success", true,
                "contextId", "default", "tabId", "main", "snapshot", "[menu_ref] button"));
        assertNull(session.validate("browser_hover", Map.of("ref", "menu_ref")));
        assertNull(session.validate("browser_scroll", Map.of("ref", "menu_ref")));
        assertNull(session.validate("browser_select", Map.of("ref", "menu_ref")));

        session.observe("browser_history", Map.of("success", true,
                "contextId", "default", "tabId", "main", "url", "https://previous.example"));

        assertTrue(session.currentRefs().isEmpty());
        assertTrue(session.validate("browser_hover", Map.of("ref", "menu_ref")).contains("stale"));
    }
}
