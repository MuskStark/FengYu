package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** First-class read-only web search, separated from stateful browser interaction. */
@Component
public class WebSearchTool implements ToolEffectProvider {

    static final String DEFAULT_ENDPOINT = "https://html.duckduckgo.com/html/?q=";
    private static final Pattern RESULT_LINK = Pattern.compile(
            "(?is)<a[^>]*class=[\"'][^\"']*result__a[^\"']*[\"'][^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebTextClient client;
    private final String endpoint;

    public WebSearchTool() {
        this(new SafeWebTextClient(), System.getProperty("fengyu.web.search.endpoint", DEFAULT_ENDPOINT));
    }

    WebSearchTool(WebTextClient client, String endpoint) {
        this.client = client;
        this.endpoint = endpoint;
    }

    @Override
    public ToolEffect effectFor(String toolName) {
        return ToolEffect.READ;
    }

    @Tool(name = "web_search",
          description = "Search the public web and return compact title/URL results. Use web_fetch to read a result; use browser_* only when interaction is required.")
    public String search(
            @ToolParam(description = "Search query.") String query,
            @ToolParam(required = false, description = "Number of results (default 5, maximum 10).") Integer count) {
        if (query == null || query.isBlank()) return WebFetchTool.failure("search query must not be blank");
        int limit = count == null ? 5 : Math.max(1, Math.min(10, count));
        try {
            String searchUrl = endpoint + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
            WebTextClient.WebResponse response = client.get(searchUrl, SafeWebTextClient.MAX_RESPONSE_BYTES);
            List<Map<String, String>> results = parseResults(response.body(), limit);
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("success", response.status() >= 200 && response.status() < 300 && !results.isEmpty());
            envelope.put("summary", "found " + results.size() + " result(s)");
            envelope.put("query", query.trim());
            envelope.put("results", results);
            return JSON.writeValueAsString(envelope);
        } catch (Exception error) {
            return WebFetchTool.failure("web search failed: " + safeMessage(error));
        }
    }

    static List<Map<String, String>> parseResults(String html, int limit) {
        List<Map<String, String>> results = new ArrayList<>();
        Matcher matcher = RESULT_LINK.matcher(html == null ? "" : html);
        while (matcher.find() && results.size() < limit) {
            String url = unwrapDuckDuckGo(WebContent.decode(matcher.group(1)));
            String title = WebContent.normalize(WebContent.decode(
                    matcher.group(2).replaceAll("(?s)<[^>]+>", " ")));
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue;
            results.add(Map.of("title", title, "url", url));
        }
        return List.copyOf(results);
    }

    private static String unwrapDuckDuckGo(String value) {
        try {
            URI uri = value.startsWith("//") ? URI.create("https:" + value) : URI.create(value);
            String query = uri.getRawQuery();
            if (query == null) return uri.toString();
            for (String part : query.split("&")) {
                int equals = part.indexOf('=');
                if (equals > 0 && "uddg".equals(part.substring(0, equals))) {
                    return URLDecoder.decode(part.substring(equals + 1), StandardCharsets.UTF_8);
                }
            }
            return uri.toString();
        } catch (Exception ignored) {
            return value;
        }
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
