package fan.summer.fengyu.plugin.store;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.List;

/** Parses the FengYu catalog JSON array (the legacy {@code fengyu.marketplace.catalog-url} format). */
@Component
public class FengYuCatalogAdapter implements MarketplaceSourceAdapter {

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override public StoreSourceType type() { return StoreSourceType.FENGYU; }

    @Override
    public List<UnifiedCatalogEntry> fetchCatalog(StoreSource src) {
        String body = httpGet(src.catalogUrl());
        return parse(src, body);
    }

    /** Package-private for direct testing against fixture JSON. */
    List<UnifiedCatalogEntry> parse(StoreSource src, String body) {
        try {
            List<JsonNode> nodes = json.readValue(body, new TypeReference<>() {});
            List<UnifiedCatalogEntry> out = new ArrayList<>(nodes.size());
            for (JsonNode n : nodes) {
                String id = text(n, "id");
                if (id == null || id.isBlank()) continue;
                out.add(new UnifiedCatalogEntry(
                    uid(src, id), src.origin(), StoreSourceType.FENGYU,
                    id, text(n, "name"), text(n, "description"),
                    new UnifiedCatalogEntry.Author(text(n, "author"), null, null),
                    text(n, "category"), List.of(), text(n, "homepage"), null,
                    new UnifiedCatalogEntry.ZipUrlSource(text(n, "downloadUrl")),
                    List.of(), List.of(), null,
                    false, null, false, false));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse FengYu catalog for " + src.origin(), e);
        }
    }

    private String httpGet(String url) {
        try {
            URI uri = URI.create(url);
            if (!List.of("https", "http").contains(uri.getScheme()))
                throw new IllegalStateException("Catalog URL must use HTTP(S): " + url);
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300)
                throw new IllegalStateException("Catalog HTTP " + resp.statusCode());
            return resp.body();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Catalog request interrupted", ie);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fetch FengYu catalog " + url, e);
        }
    }

    static String uid(StoreSource src, String name) { return src.origin() + ":FENGYU:" + name; }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
