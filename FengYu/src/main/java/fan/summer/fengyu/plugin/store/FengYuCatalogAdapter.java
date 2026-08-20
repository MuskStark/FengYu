package fan.summer.fengyu.plugin.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.plugin.market.MarketplaceCatalogEntry;
import org.springframework.stereotype.Component;

import java.io.InputStream;
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
            List<MarketplaceCatalogEntry> entries = json.readValue(body, new TypeReference<>() {});
            List<UnifiedCatalogEntry> out = new ArrayList<>(entries.size());
            for (MarketplaceCatalogEntry entry : entries) {
                String id = entry.id();
                if (id == null || id.isBlank()) continue;
                // id is the catalog's own plugin id; slugify defensively so the uid path segment
                // is always a single safe segment (PluginPackageService re-validates the .fyp id
                // at install time, but the uid must be safe before that gate runs).
                String safeId = PluginContentPathSafety.slugify(id);
                String displayName = entry.name();
                out.add(new UnifiedCatalogEntry(
                    uid(src, safeId), src.origin(), StoreSourceType.FENGYU,
                    safeId, displayName == null ? safeId : displayName, entry.description(),
                    new UnifiedCatalogEntry.Author(entry.author(), null, null),
                    entry.category(), List.of(), entry.homepage(), null,
                    entry.version(), entry.sha256(), entry.signature(), entry.keyId(),
                    new UnifiedCatalogEntry.ZipUrlSource(entry.downloadUrl()),
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
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = resp.body()) {
                if (resp.statusCode() < 200 || resp.statusCode() >= 300)
                    throw new IllegalStateException("Catalog HTTP " + resp.statusCode());
                return BoundedHttp.readAtMost(body, BoundedHttp.MAX_CATALOG_BYTES);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Catalog request interrupted", ie);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fetch FengYu catalog " + url, e);
        }
    }

    static String uid(StoreSource src, String name) { return src.origin() + ":FENGYU:" + name; }

}
