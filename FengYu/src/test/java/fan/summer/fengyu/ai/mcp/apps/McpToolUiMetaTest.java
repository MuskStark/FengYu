package fan.summer.fengyu.ai.mcp.apps;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing and projection rules for MCP Apps tool/resource {@code _meta}. */
class McpToolUiMetaTest {

    @Test
    void parsesModernUiMeta() {
        McpToolUiMeta.McpToolUiDescriptor descriptor = McpToolUiMeta.parseToolUi(Map.of(
                "ui", Map.of("resourceUri", "ui://widget/main.html",
                        "visibility", List.of("model"),
                        "csp", Map.of("connectDomains", List.of("wss://example.com")),
                        "prefersBorder", true,
                        "showInline", true),
                "openai/ui", Map.of("preferredModelDisplayMode", "fullscreen")));
        assertEquals("ui://widget/main.html", descriptor.resourceUri());
        assertEquals(List.of("model"), descriptor.visibility());
        assertEquals("fullscreen", descriptor.preferredDisplayMode());
        assertEquals(List.of("wss://example.com"), descriptor.csp().connectDomains());
        assertEquals(Boolean.TRUE, descriptor.prefersBorder());
        assertEquals(Boolean.TRUE, descriptor.showInline());
    }

    @Test
    void acceptsLegacyFlatKeyAndOpenaiAliases() {
        McpToolUiMeta.McpToolUiDescriptor legacy = McpToolUiMeta.parseToolUi(Map.of(
                "ui/resourceUri", "ui://legacy/a.html"));
        assertEquals("ui://legacy/a.html", legacy.resourceUri());

        McpToolUiMeta.McpToolUiDescriptor openai = McpToolUiMeta.parseToolUi(Map.of(
                "openai/outputTemplate", "ui://openai/b.html",
                "openai/widgetCSP", Map.of("resource_domains", List.of("https://cdn.example.com")),
                "openai/widgetPrefersBorder", true));
        assertEquals("ui://openai/b.html", openai.resourceUri());
        assertEquals(List.of("https://cdn.example.com"), openai.csp().resourceDomains());
        assertEquals(Boolean.TRUE, openai.prefersBorder());
    }

    @Test
    void visibilityDefaultsWithoutResourceUriYieldsNullDescriptor() {
        assertEquals(McpToolUiMeta.McpToolUiDescriptor.DEFAULT_VISIBILITY,
                McpToolUiMeta.readToolVisibility(Map.of("ui", Map.of())));
        assertNull(McpToolUiMeta.parseToolUi(Map.of("ui", Map.of("prefersBorder", true))));
        assertNull(McpToolUiMeta.parseToolUi(null));
    }

    @Test
    void rejectsNonUiSchemeAndOversizedUris() {
        assertNull(McpToolUiMeta.parseToolUi(Map.of("ui", Map.of("resourceUri", "https://not-ui"))));
        assertNull(McpToolUiMeta.parseToolUi(Map.of("ui",
                Map.of("resourceUri", "ui://" + "a".repeat(3000)))));
    }

    @Test
    void originNormalizationAllowsOnlyHttpsWssAndExactLoopback() {
        assertEquals("https://app.example.com", McpToolUiMeta.normalizeCspOrigin("https://app.example.com"));
        assertEquals("wss://ws.example.com:8443", McpToolUiMeta.normalizeCspOrigin("wss://ws.example.com:8443"));
        assertEquals("http://127.0.0.1:8765", McpToolUiMeta.normalizeCspOrigin("http://127.0.0.1:8765"));
        assertNull(McpToolUiMeta.normalizeCspOrigin("http://localhost:9000"));     // hosts-file rewriteable
        assertNull(McpToolUiMeta.normalizeCspOrigin("http://192.168.1.10"));       // private net
        assertNull(McpToolUiMeta.normalizeCspOrigin("ftp://example.com"));         // scheme
        assertNull(McpToolUiMeta.normalizeCspOrigin("https://u:p@example.com"));   // credentials
        assertNull(McpToolUiMeta.normalizeCspOrigin("https://example.com/path"));  // path
        assertNull(McpToolUiMeta.normalizeCspOrigin("not a url"));
    }

    @Test
    void resourceMetaParsesSizesAndPermissionsOnly() {
        McpToolUiMeta.McpAppResourceMeta meta = McpToolUiMeta.parseResourceMeta(Map.of(
                "ui", Map.of(
                        "permissions", Map.of("camera", Map.of(), "unknown", Map.of()),
                        "prefersBorder", true),
                "openai/widgetHeightHint", 320,
                "openai/widgetMinFrameHeight", 120,
                "openai/widgetShowCodexWidgetInline", true));
        assertEquals(List.of("camera"), meta.permissions());
        assertEquals(320, meta.heightHint());
        assertEquals(120, meta.minFrameHeight());
        assertEquals(Boolean.TRUE, meta.showInline());
        assertNull(McpToolUiMeta.parseResourceMeta(Map.of("ui", Map.of("unknownKey", 1))));
    }

    @Test
    void appPageCspDefaultsToDenyAndHonorsDeclaredDomains() {
        String bare = McpToolUiMeta.buildAppPageCsp(null, McpToolUiMeta.HOST_FRAME_ANCESTORS);
        assertTrue(bare.startsWith("default-src 'none'"));
        assertTrue(bare.contains("connect-src 'none'"));
        assertTrue(bare.contains("frame-src 'none'"));
        assertTrue(bare.contains("script-src 'self' 'unsafe-inline' blob: data:"));
        assertTrue(bare.contains("frame-ancestors 'http://127.0.0.1:*'"));

        String declared = McpToolUiMeta.buildAppPageCsp(new McpToolUiMeta.McpUiCsp(
                List.of("wss://example.com"), List.of("https://cdn.example.com"),
                null, List.of("https://cdn.example.com")), null);
        assertTrue(declared.contains("connect-src wss://example.com"));
        assertTrue(declared.contains("https://cdn.example.com"));
        assertFalse(declared.contains("frame-src 'none' https://cdn.example.com"));
        assertTrue(declared.contains("frame-ancestors 'self'"));
    }

    @Test
    void projectionBoundsOversizedJsonWhole() {
        McpToolUiMeta.UiToolInfo info = new McpToolUiMeta.UiToolInfo("srv", "Server", "widget",
                "server__widget", McpToolUiMeta.parseToolUi(
                        Map.of("ui", Map.of("resourceUri", "ui://w/main.html"))));
        Map<String, Object> big = Map.of("blob", "x".repeat(80 * 1024));
        Map<String, Object> ui = McpToolUiMeta.projectUiPayload(info,
                new McpToolUiMeta.CapturedResult(true, big, Map.of("k", "v"), null));
        assertEquals("srv", ui.get("serverId"));
        assertEquals(Boolean.TRUE, ui.get("isError"));
        assertFalse(ui.containsKey("structuredContent"));
        assertEquals(Boolean.TRUE, ui.get("truncated"));
        assertTrue(((Number) ui.get("truncatedBytes")).longValue() >= 64 * 1024);
        assertEquals("{\"k\":\"v\"}", ui.get("widgetMeta"));

        Map<String, Object> small = McpToolUiMeta.projectUiPayload(info,
                new McpToolUiMeta.CapturedResult(false, Map.of("answer", 42), null, null));
        assertTrue(((String) small.get("structuredContent")).contains("42"));
        assertFalse(small.containsKey("truncated"));
    }
}
