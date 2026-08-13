package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Lightweight bounded page retrieval for reading; interactive work stays in BrowserTool. */
@Component
public class WebFetchTool implements ToolEffectProvider {

    static final int DEFAULT_MAX_CHARS = 64_000;
    static final int MAX_CHARS = 200_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebTextClient client;

    public WebFetchTool() {
        this(new SafeWebTextClient());
    }

    WebFetchTool(WebTextClient client) {
        this.client = client;
    }

    @Override
    public ToolEffect effectFor(String toolName) {
        return ToolEffect.READ;
    }

    @Tool(name = "web_fetch",
          description = "Fetch a public http(s) page without opening the interactive browser. Returns bounded readable text, final URL, title, HTTP status, and content type. Private/local network targets are rejected.")
    public String fetch(
            @ToolParam(description = "Absolute public http(s) URL.") String url,
            @ToolParam(required = false,
                       description = "Maximum returned text characters (default 64000, maximum 200000).")
            Integer maxChars) {
        int limit = maxChars == null ? DEFAULT_MAX_CHARS : Math.max(1, Math.min(MAX_CHARS, maxChars));
        try {
            WebTextClient.WebResponse response = client.get(url, SafeWebTextClient.MAX_RESPONSE_BYTES);
            String text = WebContent.text(response.body(), response.contentType());
            boolean truncated = text.length() > limit;
            if (truncated) text = text.substring(0, limit);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", response.status() >= 200 && response.status() < 300);
            result.put("summary", "fetched " + response.url());
            result.put("url", response.url());
            result.put("status", response.status());
            result.put("contentType", response.contentType());
            result.put("title", WebContent.title(response.body()));
            result.put("text", text);
            result.put("truncated", truncated);
            return JSON.writeValueAsString(result);
        } catch (Exception error) {
            return failure("web fetch failed: " + safeMessage(error));
        }
    }

    static String failure(String summary) {
        try {
            return JSON.writeValueAsString(Map.of("success", false,
                    "summary", summary.replaceAll("[\\r\\n]", " ")));
        } catch (Exception ignored) {
            return "{\"success\":false,\"summary\":\"web fetch failed\"}";
        }
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
