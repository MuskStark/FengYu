package fan.summer.fengyu.plugin.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the configured catalog and merges it with packages installed on this host. */
@Service
public class PluginMarketplaceService {

    private static final long MAX_CATALOG_BYTES = 8L * 1024 * 1024;

    private final ObjectMapper json;
    private final PluginPackageService packages;
    private final String catalogUrl;
    private final HttpClient http;

    public PluginMarketplaceService(PluginPackageService packages,
            @Value("${fengyu.marketplace.catalog-url:}") String catalogUrl) {
        this.json = JsonMapper.builder().findAndAddModules().build();
        this.packages = packages;
        this.catalogUrl = catalogUrl == null ? "" : catalogUrl.trim();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public List<MarketplacePlugin> list(String locale) {
        Map<String, MarketplaceCatalogEntry> catalog = new LinkedHashMap<>();
        for (MarketplaceCatalogEntry entry : fetchCatalog()) {
            if (entry.id() != null && !entry.id().isBlank()) catalog.put(entry.id(), entry);
        }
        Map<String, PluginManifest> installed = new LinkedHashMap<>();
        for (PluginManifest manifest : packages.installed()) installed.put(manifest.id(), manifest);

        List<MarketplacePlugin> result = new ArrayList<>();
        for (MarketplaceCatalogEntry entry : catalog.values()) {
            PluginManifest local = installed.remove(entry.id());
            result.add(toView(entry, local, locale));
        }
        for (PluginManifest local : installed.values()) result.add(toView(null, local, locale));
        return result.stream().sorted((a, b) -> a.name().compareToIgnoreCase(b.name())).toList();
    }

    public PluginManifest install(String id) throws IOException, InterruptedException {
        return install(id, false);
    }

    public PluginManifest install(String id, boolean confirmPermissionEscalation)
            throws IOException, InterruptedException {
        MarketplaceCatalogEntry entry = fetchCatalog().stream()
            .filter(item -> item.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Plugin is not present in the configured catalog: " + id));
        if (entry.downloadUrl() == null || entry.downloadUrl().isBlank()) {
            throw new IllegalArgumentException("Catalog entry has no download URL: " + id);
        }
        return packages.installFromUrl(entry.downloadUrl(), entry.sha256(), entry.signature(),
            entry.keyId(), confirmPermissionEscalation);
    }

    private List<MarketplaceCatalogEntry> fetchCatalog() {
        if (catalogUrl.isBlank()) return List.of();
        try {
            URI uri = URI.create(catalogUrl);
            if (!List.of("https", "http").contains(uri.getScheme())) {
                throw new IllegalStateException("Marketplace catalog URL must use HTTP(S)");
            }
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<InputStream> response =
                    http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Marketplace catalog returned HTTP " + response.statusCode());
            }
            // Cap the catalog body: an unbounded ofString() would let a broken/hostile
            // catalog URL OOM the host. 8 MB is orders of magnitude above a real catalog.
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
            byte[] buffer = new byte[8 * 1024];
            long total = 0;
            try (InputStream body = response.body()) {
                for (int count; (count = body.read(buffer)) >= 0;) {
                    total += count;
                    if (total > MAX_CATALOG_BYTES) {
                        throw new IllegalStateException("Marketplace catalog exceeds 8 MB");
                    }
                    bytes.write(buffer, 0, count);
                }
            }
            return json.readValue(bytes.toString(java.nio.charset.StandardCharsets.UTF_8),
                    new TypeReference<>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read marketplace catalog", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Marketplace catalog request was interrupted", e);
        }
    }

    private MarketplacePlugin toView(MarketplaceCatalogEntry remote, PluginManifest local, String locale) {
        boolean installed = local != null;
        String id = installed ? local.id() : remote.id();
        String available = remote != null ? remote.version() : local.version();
        // Localize name/description from the installed manifest when present — remote catalog
        // entries are single-language, so a locally installed package overrides them with the
        // user's locale. Catalog-only rows (not yet installed) keep the catalog's English strings.
        return new MarketplacePlugin(
            id,
            installed ? ManifestI18n.name(local, locale) : (remote != null ? remote.name() : local.name()),
            installed ? ManifestI18n.description(local, locale)
                : (remote != null ? remote.description() : local.description()),
            available,
            installed ? local.version() : null,
            remote != null ? remote.author() : local.author(),
            remote != null ? remote.icon() : local.icon(),
            remote != null ? remote.category() : local.category(),
            remote != null ? safe(remote.permissions()) : safe(local.permissions()),
            remote != null ? remote.homepage() : local.homepage(),
            remote != null ? remote.downloadUrl() : null,
            remote != null ? remote.official() : local.official(),
            installed,
            installed && packages.isEnabled(id),
            installed && remote != null && compareVersions(remote.version(), local.version()) > 0
        );
    }

    private static List<String> safe(List<String> value) {
        return value == null ? List.of() : List.copyOf(value);
    }

    /** Public so other components (e.g. the official-plugin seeder) can order semver versions. */
    public static int compareVersions(String left, String right) {
        return SemanticVersion.compare(left, right);
    }
}
