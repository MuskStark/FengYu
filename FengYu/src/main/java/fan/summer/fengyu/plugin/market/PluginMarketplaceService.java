package fan.summer.fengyu.plugin.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
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

    public List<MarketplacePlugin> list() {
        Map<String, MarketplaceCatalogEntry> catalog = new LinkedHashMap<>();
        for (MarketplaceCatalogEntry entry : fetchCatalog()) {
            if (entry.id() != null && !entry.id().isBlank()) catalog.put(entry.id(), entry);
        }
        Map<String, PluginManifest> installed = new LinkedHashMap<>();
        for (PluginManifest manifest : packages.installed()) installed.put(manifest.id(), manifest);

        List<MarketplacePlugin> result = new ArrayList<>();
        for (MarketplaceCatalogEntry entry : catalog.values()) {
            PluginManifest local = installed.remove(entry.id());
            result.add(toView(entry, local));
        }
        for (PluginManifest local : installed.values()) result.add(toView(null, local));
        return result.stream().sorted((a, b) -> a.name().compareToIgnoreCase(b.name())).toList();
    }

    public PluginManifest install(String id) throws IOException, InterruptedException {
        MarketplaceCatalogEntry entry = fetchCatalog().stream()
            .filter(item -> item.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Plugin is not present in the configured catalog: " + id));
        if (entry.downloadUrl() == null || entry.downloadUrl().isBlank()) {
            throw new IllegalArgumentException("Catalog entry has no download URL: " + id);
        }
        return packages.installFromUrl(entry.downloadUrl());
    }

    private List<MarketplaceCatalogEntry> fetchCatalog() {
        if (catalogUrl.isBlank()) return List.of();
        try {
            URI uri = URI.create(catalogUrl);
            if (!List.of("https", "http").contains(uri.getScheme())) {
                throw new IllegalStateException("Marketplace catalog URL must use HTTP(S)");
            }
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Marketplace catalog returned HTTP " + response.statusCode());
            }
            return json.readValue(response.body(), new TypeReference<>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read marketplace catalog", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Marketplace catalog request was interrupted", e);
        }
    }

    private MarketplacePlugin toView(MarketplaceCatalogEntry remote, PluginManifest local) {
        boolean installed = local != null;
        String id = installed ? local.id() : remote.id();
        String available = remote != null ? remote.version() : local.version();
        return new MarketplacePlugin(
            id,
            remote != null ? remote.name() : local.name(),
            remote != null ? remote.description() : local.description(),
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

    static int compareVersions(String left, String right) {
        int[] a = numericVersion(left);
        int[] b = numericVersion(right);
        for (int i = 0; i < 3; i++) {
            int comparison = Integer.compare(a[i], b[i]);
            if (comparison != 0) return comparison;
        }
        return left.compareTo(right);
    }

    private static int[] numericVersion(String version) {
        String[] parts = version.split("[-+]", 2)[0].split("\\.");
        int[] out = new int[3];
        for (int i = 0; i < Math.min(parts.length, 3); i++) {
            try { out[i] = Integer.parseInt(parts[i]); }
            catch (NumberFormatException ignored) { out[i] = 0; }
        }
        return out;
    }
}
