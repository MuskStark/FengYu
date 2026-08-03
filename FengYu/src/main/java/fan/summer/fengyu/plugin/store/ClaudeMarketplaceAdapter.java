package fan.summer.fengyu.plugin.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Parses {@code .claude-plugin/marketplace.json}. */
@Component
public class ClaudeMarketplaceAdapter implements MarketplaceSourceAdapter {

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override public StoreSourceType type() { return StoreSourceType.CLAUDE; }

    @Override
    public List<UnifiedCatalogEntry> fetchCatalog(StoreSource src) {
        return parse(src, httpGet(src.catalogUrl()));
    }

    List<UnifiedCatalogEntry> parse(StoreSource src, String body) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode renames = root.get("renames");
            JsonNode plugins = root.get("plugins");
            if (plugins == null || !plugins.isArray()) return List.of();
            List<UnifiedCatalogEntry> out = new ArrayList<>();
            for (JsonNode p : plugins) {
                UnifiedCatalogEntry e = translate(src, p, renames);
                if (e != null) out.add(e);
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse Claude marketplace for " + src.origin(), e);
        }
    }

    private UnifiedCatalogEntry translate(StoreSource src, JsonNode p, JsonNode renames) {
        String rawName = text(p, "name");
        if (rawName == null) return null;
        String name = applyRenames(rawName, renames);

        UnifiedCatalogEntry.SourceRef ref;
        String pinnedSha = null;
        JsonNode s = p.get("source");
        if (s == null || s.isTextual()) return null; // local path string — skip
        String kind = text(s, "source");
        if ("url".equals(kind)) {
            pinnedSha = text(s, "sha");
            ref = new UnifiedCatalogEntry.GitUrlSource(text(s, "url"), pinnedSha);
        } else if ("git-subdir".equals(kind)) {
            pinnedSha = text(s, "sha");
            ref = new UnifiedCatalogEntry.GitSubdirSource(text(s, "url"), text(s, "path"), text(s, "ref"), pinnedSha);
        } else {
            return null; // unknown source kind — skip
        }

        List<String> keywords = stringList(p.get("keywords"));
        return new UnifiedCatalogEntry(
            src.origin() + ":CLAUDE:" + name, src.origin(), StoreSourceType.CLAUDE,
            name, name, text(p, "description"), author(p.get("author")),
            text(p, "category"), keywords, text(p, "homepage"),
            pinnedSha, ref, List.of(), List.of(), null,
            false, null, false, false);
    }

    private static String applyRenames(String name, JsonNode renames) {
        if (renames == null) return name;
        JsonNode mapped = renames.get(name);
        return mapped == null ? name : mapped.asText(name);
    }

    private static UnifiedCatalogEntry.Author author(JsonNode a) {
        if (a == null || a.isNull()) return null;
        return new UnifiedCatalogEntry.Author(text(a, "name"), text(a, "email"), text(a, "url"));
    }

    private static List<String> stringList(JsonNode arr) {
        if (arr == null || !arr.isArray()) return List.of();
        List<String> out = new ArrayList<>(arr.size());
        for (Iterator<JsonNode> it = arr.elements(); it.hasNext(); ) out.add(it.next().asText());
        return List.copyOf(out);
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private String httpGet(String url) {
        try {
            URI uri = URI.create(url);
            if (!List.of("https", "http").contains(uri.getScheme()))
                throw new IllegalStateException("Marketplace URL must use HTTP(S): " + url);
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300)
                throw new IllegalStateException("Marketplace HTTP " + resp.statusCode());
            return resp.body();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Marketplace request interrupted", ie);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fetch Claude marketplace " + url, e);
        }
    }
}
