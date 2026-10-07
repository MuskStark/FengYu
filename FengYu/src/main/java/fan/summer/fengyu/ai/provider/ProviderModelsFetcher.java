package fan.summer.fengyu.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.ai.util.BaseUrlNormalizer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Best-effort live model listing for one provider: asks the vendor's own models
 * endpoint ({@code GET {base}/models} in the OpenAI-compatible contract,
 * {@code GET {base}/v1/models} for Anthropic, {@code GET {base}/api/tags} for
 * Ollama) what it serves, so the UI can offer a picker instead of a free-text
 * model field. Base URLs are normalized via {@link BaseUrlNormalizer} — the
 * probe hits exactly the endpoint family the live chat client ({@code
 * ChatModelConfig}) uses. Failures are outcomes, not exceptions: the caller
 * renders the configured model plus whatever arrived.
 */
public final class ProviderModelsFetcher {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(4);
    private static final int MAX_MODELS = 200;

    private ProviderModelsFetcher() {
    }

    /** @return model ids the endpoint reports; empty (never null) when unreachable/unparseable. */
    public static List<String> fetch(ProviderDefinition d, String apiKey) {
        try {
            List<String> ids = switch (d.protocol()) {
                case OPENAI_CHAT -> readIds(get(
                        BaseUrlNormalizer.normalizeForSdk(d.baseUrl(),
                                BaseUrlNormalizer.Provider.OPENAI_COMPATIBLE) + "/models",
                        "Authorization", "Bearer " + apiKey));
                case ANTHROPIC_MESSAGES -> readIds(get(
                        BaseUrlNormalizer.normalizeForSdk(d.baseUrl(),
                                BaseUrlNormalizer.Provider.ANTHROPIC) + "/v1/models",
                        "x-api-key", apiKey,
                        "anthropic-version", "2023-06-01"));
                case OLLAMA -> readOllamaTags(d.baseUrl());
            };
            Set<String> unique = new LinkedHashSet<>(ids);
            List<String> out = new ArrayList<>(unique);
            return out.size() > MAX_MODELS ? out.subList(0, MAX_MODELS) : out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** OpenAI/Anthropic models responses both nest ids under {@code data[].id}. */
    private static List<String> readIds(HttpResponse<String> res) throws Exception {
        if (res.statusCode() / 100 != 2) return List.of();
        JsonNode data = JSON.readTree(res.body()).path("data");
        if (!data.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode m : data) {
            String id = m.path("id").asText("");
            if (!id.isBlank()) out.add(id);
        }
        return out;
    }

    private static List<String> readOllamaTags(String baseUrl) throws Exception {
        HttpResponse<String> res = get(stripSlash(baseUrl) + "/api/tags");
        if (res.statusCode() / 100 != 2) return List.of();
        JsonNode models = JSON.readTree(res.body()).path("models");
        if (!models.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode m : models) {
            String name = m.path("name").asText("");
            if (!name.isBlank()) out.add(name);
        }
        return out;
    }

    private static HttpResponse<String> get(String url, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT);
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return HttpClient.newHttpClient().send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String stripSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
