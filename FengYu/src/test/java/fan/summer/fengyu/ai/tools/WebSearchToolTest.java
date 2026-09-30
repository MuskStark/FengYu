package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebSearchToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void parsesAndUnwrapsCompactSearchResults() throws Exception {
        String html = """
                <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fdocs">Example <b>Docs</b></a>
                <a class="result__a" href="https://second.example/path">Second</a>
                """;
        WebTextClient client = (url, max) -> new WebTextClient.WebResponse(url, 200, "text/html", html);
        WebSearchTool tool = new WebSearchTool(client, "https://search.example/?q=");

        Map<?, ?> envelope = JSON.readValue(tool.search("fengyu browser", 1), Map.class);
        assertEquals(Boolean.TRUE, envelope.get("success"));
        List<?> results = (List<?>) envelope.get("results");
        assertEquals(1, results.size());
        assertEquals("https://example.com/docs", ((Map<?, ?>) results.getFirst()).get("url"));
        assertEquals("Example Docs", ((Map<?, ?>) results.getFirst()).get("title"));
        assertEquals(ToolEffect.READ, tool.effectFor("web_search"));
    }
}
