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

/** Parses {@code .agents/plugins/marketplace.json} (Codex). Local sources are resolved against the repo. */
@Component
public class CodexMarketplaceAdapter implements MarketplaceSourceAdapter {

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override public StoreSourceType type() { return StoreSourceType.CODEX; }

    @Override
    public List<UnifiedCatalogEntry> fetchCatalog(StoreSource src) {
        return parse(src, httpGet(src.catalogUrl()));
    }

    List<UnifiedCatalogEntry> parse(StoreSource src, String body) {
        try {
            JsonNode root = json.readTree(body);
            String marketDisplayName = root.path("interface").path("displayName").asText(null);
            JsonNode marketInterface = root.get("interface");
            JsonNode plugins = root.get("plugins");
            if (plugins == null || !plugins.isArray()) return List.of();

            // Resolve the repo the marketplace lives in, so local sources can be cloned.
            GitHubUrlResolver.Resolved resolved = GitHubUrlResolver.resolve(src.catalogUrl());

            List<UnifiedCatalogEntry> out = new ArrayList<>();
            for (JsonNode p : plugins) {
                UnifiedCatalogEntry e = translate(src, p, marketDisplayName, marketInterface, resolved);
                if (e != null) out.add(e);
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse Codex marketplace for " + src.origin(), e);
        }
    }

    private UnifiedCatalogEntry translate(StoreSource src, JsonNode p,
            String marketDisplayName, JsonNode marketInterface,
            GitHubUrlResolver.Resolved resolved) {
        String name = text(p, "name");
        if (name == null) return null;
        JsonNode s = p.get("source");
        String kind = s == null ? null : text(s, "source");
        if (!"local".equals(kind)) return null; // only local sources supported for Codex
        if (resolved == null) return null;      // can't resolve the repo — skip

        String repoUrl = resolved.repoUrl();
        String ref = resolved.ref();
        String path = text(s, "path");
        var ref0 = new UnifiedCatalogEntry.GitLocalInRepoSource(repoUrl, ref, path);

        String displayName = text(p, "interface", "displayName");
        if (displayName == null) displayName = marketDisplayName != null ? marketDisplayName : name;

        // Plugin-level interface wins; otherwise fall back to the marketplace-level block.
        JsonNode pluginInterface = p.get("interface");
        JsonNode iface = (pluginInterface != null && !pluginInterface.isNull()) ? pluginInterface : marketInterface;

        return new UnifiedCatalogEntry(
            src.origin() + ":CODEX:" + name, src.origin(), StoreSourceType.CODEX,
            name, displayName, text(p, "description"),
            author(p.get("author")),
            text(p, "category"), stringList(p.get("keywords")), text(p, "homepage"),
            null, ref0, List.of(), List.of(), interfaceMeta(iface, displayName),
            false, null, false, false);
    }

    private static UnifiedCatalogEntry.InterfaceMeta interfaceMeta(JsonNode iface, String displayName) {
        if (iface == null || iface.isNull()) return null;
        return new UnifiedCatalogEntry.InterfaceMeta(
            displayName,
            text(iface, "shortDescription"), text(iface, "longDescription"),
            text(iface, "developerName"), text(iface, "category"),
            stringList(iface.get("capabilities")),
            text(iface, "websiteURL"), text(iface, "privacyPolicyURL"), text(iface, "termsOfServiceURL"),
            stringList(iface.get("defaultPrompt")),
            text(iface, "brandColor"), text(iface, "composerIcon"),
            text(iface, "logo"), text(iface, "logoDark"),
            stringList(iface.get("screenshots")));
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

    private static String text(JsonNode n, String f1, String f2) {
        JsonNode v = n.path(f1).path(f2);
        return (v == null || v.isMissingNode() || v.isNull()) ? null : v.asText();
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
            throw new IllegalStateException("Cannot fetch Codex marketplace " + url, e);
        }
    }
}
