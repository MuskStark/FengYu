package fan.summer.fengyu.plugin.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.plugin.market.MarketplaceCatalogEntry;
import fan.summer.fengyu.store.StoreClient;
import fan.summer.fengyu.store.StoreModels.CatalogItem;
import fan.summer.fengyu.store.StoreModels.CatalogPage;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Two fetch modes share the FENGYU source type:
 *
 * <ul>
 *   <li><b>blank {@code catalogUrl} (the default source)</b> — the official Infinia store's
 *       own catalog ({@link StoreClient} browse, all listing types). Entries carry a
 *       {@link UnifiedCatalogEntry.StoreCoordinateSource} and install through the store
 *       transaction pipeline, so a fresh install browses and installs out of the box with no
 *       configured URL.</li>
 *   <li><b>explicit {@code catalogUrl}</b> — the legacy self-hosted JSON-array catalog format
 *       ({@code fengyu.marketplace.catalog-url}), entries with direct {@code .fyp} download
 *       URLs.</li>
 * </ul>
 */
@Component
public class FengYuCatalogAdapter implements MarketplaceSourceAdapter {

    /** Mirrors StoreService: bound on followed catalog cursor pages, 100 rows each. */
    static final int MAX_CATALOG_PAGES = 30;
    static final int PAGE_SIZE = 100;

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final StoreClient client;

    public FengYuCatalogAdapter(StoreClient client) {
        this.client = client;
    }

    @Override public StoreSourceType type() { return StoreSourceType.FENGYU; }

    @Override
    public List<UnifiedCatalogEntry> fetchCatalog(StoreSource src) {
        if (src.catalogUrl() == null || src.catalogUrl().isBlank()) {
            return fetchOfficialCatalog(src);
        }
        return parse(src, httpGet(src.catalogUrl()));
    }

    /**
     * The official store's whole catalog (PLUGIN + SKILL + MCP), paginated through the shared
     * {@link StoreClient} channel (api-base, proxy posture, SSRF policy — one base for catalog,
     * listings, and install).
     */
    private List<UnifiedCatalogEntry> fetchOfficialCatalog(StoreSource src) {
        List<UnifiedCatalogEntry> out = new ArrayList<>();
        String cursor = null;
        try {
            for (int page = 0; page < MAX_CATALOG_PAGES; page++) {
                CatalogPage result = client.browse(null, null, cursor, PAGE_SIZE);
                for (CatalogItem item : result.items()) {
                    UnifiedCatalogEntry mapped = mapOfficial(src, item);
                    if (mapped != null) out.add(mapped);
                }
                if (result.nextCursor() == null || result.nextCursor().isBlank()) return out;
                cursor = result.nextCursor();
            }
            org.slf4j.LoggerFactory.getLogger(FengYuCatalogAdapter.class).warn(
                "Official FengYu catalog exceeded {} pages; catalog shows the first {} entries",
                MAX_CATALOG_PAGES, out.size());
            return out;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Official FengYu catalog fetch interrupted", interrupted);
        } catch (Exception e) {
            throw new IllegalStateException(
                "Cannot fetch official FengYu catalog for " + src.origin(), e);
        }
    }

    /** Maps one official store catalog row; {@code null} skips rows without a coordinate. */
    UnifiedCatalogEntry mapOfficial(StoreSource src, CatalogItem item) {
        String coordinate = item.coordinate();
        if (coordinate == null || coordinate.isBlank()) return null;
        String slug = item.slug() == null || item.slug().isBlank()
            ? PluginContentPathSafety.slugify(coordinate)
            : PluginContentPathSafety.slugify(item.slug());
        String displayName = item.name();
        return new UnifiedCatalogEntry(
            uid(src, slug), src.origin(), StoreSourceType.FENGYU,
            slug, displayName == null ? slug : displayName, item.summary(),
            new UnifiedCatalogEntry.Author(item.publisherName(), null, null),
            item.category(), List.of(), null, null,
            item.latestVersion(), null, null, null,
            new UnifiedCatalogEntry.StoreCoordinateSource(coordinate),
            List.of(), List.of(), null,
            false, null, false, false,
            fan.summer.fengyu.security.ProcessSandbox.isNativeSandboxAvailableCached());
    }

    /** Legacy self-hosted path: parse the FengYu catalog JSON array. Package-private for testing. */
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
                    false, null, false, false,
                    fan.summer.fengyu.security.ProcessSandbox.isNativeSandboxAvailableCached()));
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
