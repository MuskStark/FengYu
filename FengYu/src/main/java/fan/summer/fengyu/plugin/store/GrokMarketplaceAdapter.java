package fan.summer.fengyu.plugin.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

/** Parses the official Grok Build {@code .grok-plugin/marketplace.json} catalog format.
 *
 * <p>Grok's remote entries use the same pinned Git URL model as Claude, while its local
 * entries are paths relative to the marketplace repository, like Codex. Both shapes are
 * normalized before they reach the common, integrity-checked installer.</p>
 */
@Component
public final class GrokMarketplaceAdapter implements MarketplaceSourceAdapter {

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override public StoreSourceType type() { return StoreSourceType.GROK; }

    @Override
    public List<UnifiedCatalogEntry> fetchCatalog(StoreSource src) {
        return parse(src, httpGet(src.catalogUrl()));
    }

    List<UnifiedCatalogEntry> parse(StoreSource src, String body) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode plugins = root.get("plugins");
            if (plugins == null || !plugins.isArray()) return List.of();
            UnifiedCatalogEntry.Author owner = author(root.get("owner"));
            GitHubUrlResolver.Resolved marketplaceRepo = GitHubUrlResolver.resolve(src.catalogUrl());
            List<UnifiedCatalogEntry> out = new ArrayList<>();
            for (JsonNode plugin : plugins) {
                UnifiedCatalogEntry entry = translate(src, plugin, owner, marketplaceRepo);
                if (entry != null) out.add(entry);
            }
            return List.copyOf(out);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot parse Grok marketplace for " + src.origin(), error);
        }
    }

    private static UnifiedCatalogEntry translate(StoreSource src, JsonNode plugin,
            UnifiedCatalogEntry.Author owner, GitHubUrlResolver.Resolved marketplaceRepo) {
        String rawName = text(plugin, "name");
        if (rawName == null) return null;
        String name = PluginContentPathSafety.slugify(rawName);
        JsonNode source = plugin.get("source");
        if (source == null || !source.isObject()) return null;

        String kind = text(source, "source");
        if (kind == null) kind = text(source, "type");
        String path = text(source, "path");
        String sha = text(source, "sha");
        UnifiedCatalogEntry.SourceRef ref;
        if ("url".equals(kind)) {
            String url = text(source, "url");
            if (url == null || url.isBlank()) return null;
            ref = path == null || path.isBlank()
                    ? new UnifiedCatalogEntry.GitUrlSource(url, sha)
                    : new UnifiedCatalogEntry.GitSubdirSource(url, path, null, sha);
        } else if ("local".equals(kind) && marketplaceRepo != null && path != null && !path.isBlank()) {
            ref = new UnifiedCatalogEntry.GitLocalInRepoSource(
                    marketplaceRepo.repoUrl(), marketplaceRepo.ref(), path);
        } else {
            return null;
        }

        return new UnifiedCatalogEntry(
                src.origin() + ":GROK:" + name, src.origin(), StoreSourceType.GROK,
                name, name, text(plugin, "description"), author(plugin.get("author"), owner),
                text(plugin, "category"), searchTerms(plugin), text(plugin, "homepage"), sha,
                ref, List.of(), List.of(), null, false, null, false, false);
    }

    /** Grok's {@code domains} are also discovery hints, so make them searchable in FengYu. */
    private static List<String> searchTerms(JsonNode plugin) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        addStrings(terms, plugin.get("keywords"));
        addStrings(terms, plugin.get("domains"));
        return List.copyOf(terms);
    }

    private static void addStrings(LinkedHashSet<String> target, JsonNode values) {
        if (values == null || !values.isArray()) return;
        for (Iterator<JsonNode> it = values.elements(); it.hasNext();) {
            String value = it.next().asText(null);
            if (value != null && !value.isBlank()) target.add(value);
        }
    }

    private static UnifiedCatalogEntry.Author author(JsonNode value) {
        return author(value, null);
    }

    private static UnifiedCatalogEntry.Author author(JsonNode value, UnifiedCatalogEntry.Author fallback) {
        if (value == null || value.isNull()) return fallback;
        return new UnifiedCatalogEntry.Author(text(value, "name"), text(value, "email"), text(value, "url"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String httpGet(String url) {
        try {
            URI uri = URI.create(url);
            if (!List.of("https", "http").contains(uri.getScheme())) {
                throw new IllegalStateException("Marketplace URL must use HTTP(S): " + url);
            }
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("Marketplace HTTP " + response.statusCode());
                }
                return BoundedHttp.readAtMost(body, BoundedHttp.MAX_CATALOG_BYTES);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Marketplace request interrupted", interrupted);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot fetch Grok marketplace " + url, error);
        }
    }
}
