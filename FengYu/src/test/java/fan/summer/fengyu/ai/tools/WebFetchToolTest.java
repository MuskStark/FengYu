package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebFetchToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void returnsReadableBoundedPageTextAsReadOnlyTool() throws Exception {
        WebTextClient client = (url, max) -> new WebTextClient.WebResponse(
                "https://example.com/final", 200, "text/html",
                "<html><title>Example &amp; Docs</title><script>ignore()</script><body><h1>Hello</h1><p>World</p></body></html>");
        WebFetchTool tool = new WebFetchTool(client);

        Map<?, ?> result = JSON.readValue(tool.fetch("https://example.com", 16), Map.class);
        assertEquals(Boolean.TRUE, result.get("success"));
        assertEquals("Example & Docs", result.get("title"));
        assertEquals(16, ((String) result.get("text")).length());
        assertFalse(((String) result.get("text")).contains("ignore"));
        assertEquals(Boolean.TRUE, result.get("truncated"));
        assertEquals(ToolEffect.READ, tool.effectFor("web_fetch"));
    }

    @Test
    void rejectsLocalAndNonHttpTargets() {
        assertThrows(IllegalArgumentException.class, () -> SafeWebTextClient.checkedUri("file:///tmp/a"));
        assertThrows(Exception.class,
                () -> SafeWebTextClient.assertPublicHost(URI.create("http://127.0.0.1/private")));
    }
}
