package fan.summer.fengyu.ai.tools;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BrowserToolTest {

    /** Test subclass that swaps the real HTTP client for an in-memory stub. */
    private static final class StubTool extends BrowserTool {
        private final java.util.function.BiFunction<String, Map<String, Object>, Map<String, Object>> stub;
        StubTool(java.util.function.BiFunction<String, Map<String, Object>, Map<String, Object>> stub) {
            // Pass null for the real client: invokeBridge() is overridden below so the HTTP
            // bridge is never touched. Using the package-private injection constructor avoids
            // the no-arg BrowserTool() path, which eagerly reads FENGYU_BROWSER_BRIDGE_* env.
            super(null);
            this.stub = stub;
        }
        @Override
        protected Map<String, Object> invokeBridge(String method, Map<String, Object> params, int timeoutSeconds) {
            return stub.apply(method, params);
        }
    }

    private static Map<String, Object> parse(String json) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
    }

    @Test
    void navigateReturnsContractEnvelope() throws Exception {
        var tool = new StubTool((m, p) -> Map.of("success", true, "summary", "navigated to https://example.com",
                "url", "https://example.com", "title", "Example"));
        Map<String, Object> r = parse(tool.navigate("https://example.com", "load"));
        assertEquals(Boolean.TRUE, r.get("success"));
        assertEquals("Example", r.get("title"));
    }

    @Test
    void getTextTruncatesBeyondTextCap() throws Exception {
        // Electron returns full text; BrowserTool must cap at TEXT_CAP.
        String huge = "a".repeat(70_000);
        var tool = new StubTool((m, p) -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("success", true); e.put("summary", "read text");
            e.put("text", huge); e.put("length", huge.length());
            return e;
        });
        Map<String, Object> r = parse(tool.getText(null));
        String text = (String) r.get("text");
        assertTrue(text.endsWith("…[truncated]"));
        assertTrue(text.length() <= BrowserTool.TEXT_CAP);
    }

    @Test
    void queryLimitsSamplesToFive() throws Exception {
        var all = List.of("a", "b", "c", "d", "e", "f", "g");
        var tool = new StubTool((m, p) -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("success", true); e.put("summary", "matched 7 element(s)");
            e.put("count", 7); e.put("samples", all);
            return e;
        });
        Map<String, Object> r = parse(tool.query("div"));
        @SuppressWarnings("unchecked")
        List<String> samples = (List<String>) r.get("samples");
        assertEquals(5, samples.size());
        assertEquals(7, r.get("count"));
    }

    @Test
    void bridgeUnavailableReturnsFailureEnvelopeNotException() throws Exception {
        var tool = new StubTool((m, p) -> { throw new BrowserBridgeUnavailableException("bridge down"); });
        Map<String, Object> r = parse(tool.close());
        assertEquals(Boolean.FALSE, r.get("success"));
        assertTrue(((String) r.get("summary")).contains("browser bridge unavailable"));
    }

    @Test
    void degradedModeNoBridgeClientReturnsFailureNotException() throws Exception {
        // When the Electron bridge env is absent (e.g. IDE start without the shell), the
        // Spring constructor leaves client=null. Every tool call must return a friendly
        // failure envelope, not throw — so the bean registers and AI sees browser_* tools.
        var tool = new BrowserTool(null);
        Map<String, Object> r = parse(tool.navigate("https://example.com", "load"));
        assertEquals(Boolean.FALSE, r.get("success"));
        assertTrue(((String) r.get("summary")).contains("browser bridge unavailable"));
    }
}
