package fan.summer.fengyu.ai.skill;

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

/**
 * Reads the configured skill catalog and merges it with skills installed on this host.
 *
 * <p>The lifecycle twin of {@code PluginMarketplaceService}: same remote-catalog fetch, same
 * remote/local merge, same version comparison for "update available". The catalog URL comes from
 * {@code fengyu.skills.catalog-url} (default empty → no remote, degrades to local-installed only,
 * mirroring how the plugin marketplace behaves with a blank {@code fengyu.marketplace.catalog-url}).
 *
 * <p>{@link #install(String)} and update share one path (re-fetch catalog → find entry →
 * {@link SkillPackageService#installFromUrl}), exactly as the plugin marketplace does.
 *
 * @since 4.0.0
 */
@Service
public class SkillMarketplaceService {
    private final ObjectMapper json;
    private final SkillPackageService packages;
    private final String catalogUrl;
    private final HttpClient http;

    public SkillMarketplaceService(SkillPackageService packages,
            @Value("${fengyu.skills.catalog-url:}") String catalogUrl) {
        this.json = JsonMapper.builder().findAndAddModules().build();
        this.packages = packages;
        this.catalogUrl = catalogUrl == null ? "" : catalogUrl.trim();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Merges the remote catalog with locally-installed skills, sorted by name (case-insensitive). */
    public List<MarketplaceSkill> list() {
        Map<String, SkillCatalogEntry> catalog = new LinkedHashMap<>();
        for (SkillCatalogEntry entry : fetchCatalog()) {
            if (entry.id() != null && !entry.id().isBlank()) catalog.put(entry.id(), entry);
        }
        Map<String, SkillManifest> installed = new LinkedHashMap<>();
        for (SkillManifest manifest : packages.installed()) installed.put(manifest.id(), manifest);

        List<MarketplaceSkill> result = new ArrayList<>();
        for (SkillCatalogEntry entry : catalog.values()) {
            SkillManifest local = installed.remove(entry.id());
            result.add(toView(entry, local));
        }
        for (SkillManifest local : installed.values()) result.add(toView(null, local));
        return result.stream().sorted((a, b) -> a.name().compareToIgnoreCase(b.name())).toList();
    }

    /** Install (or update) a skill by id from the configured catalog. */
    public SkillManifest install(String id) throws IOException, InterruptedException {
        SkillCatalogEntry entry = fetchCatalog().stream()
            .filter(item -> item.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Skill is not present in the configured catalog: " + id));
        if (entry.downloadUrl() == null || entry.downloadUrl().isBlank()) {
            throw new IllegalArgumentException("Catalog entry has no download URL: " + id);
        }
        return packages.installFromUrl(entry.downloadUrl());
    }

    private List<SkillCatalogEntry> fetchCatalog() {
        if (catalogUrl.isBlank()) return List.of();
        try {
            URI uri = URI.create(catalogUrl);
            if (!List.of("https", "http").contains(uri.getScheme())) {
                throw new IllegalStateException("Skill marketplace catalog URL must use HTTP(S)");
            }
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Skill marketplace catalog returned HTTP " + response.statusCode());
            }
            return json.readValue(response.body(), new TypeReference<>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read skill marketplace catalog", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Skill marketplace catalog request was interrupted", e);
        }
    }

    private MarketplaceSkill toView(SkillCatalogEntry remote, SkillManifest local) {
        boolean installed = local != null;
        String id = installed ? local.id() : remote.id();
        String available = remote != null ? remote.version() : local.version();
        return new MarketplaceSkill(
            id,
            remote != null ? remote.name() : local.name(),
            remote != null ? remote.description() : local.description(),
            available,
            installed ? local.version() : null,
            remote != null ? remote.author() : local.author(),
            remote != null ? remote.icon() : local.icon(),
            remote != null ? remote.homepage() : local.homepage(),
            remote != null ? remote.downloadUrl() : null,
            remote != null ? remote.official() : local.official(),
            installed,
            installed && packages.isEnabled(id),
            installed && remote != null && compareVersions(remote.version(), local.version()) > 0
        );
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
