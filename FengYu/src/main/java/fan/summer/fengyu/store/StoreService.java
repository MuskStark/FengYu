package fan.summer.fengyu.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fan.summer.fengyu.ai.mcp.McpRuntimeManager;
import fan.summer.fengyu.ai.skill.SkillPackageService;
import fan.summer.fengyu.plugin.market.PluginHostVersion;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.store.StoreModels.CatalogItem;
import fan.summer.fengyu.store.StoreModels.CatalogPage;
import fan.summer.fengyu.store.StoreModels.DownloadTicket;
import fan.summer.fengyu.store.StoreModels.ListingDetail;
import fan.summer.fengyu.store.StoreModels.ResolveResponse;
import fan.summer.fengyu.store.StoreModels.ResolutionItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Install orchestrator for the Infinia Store (design §9): resolve → ticketed
 * download → SHA-256 verify → type-specific installer. Plugins and skills reuse
 * the host's own package services (which re-validate manifests and permissions);
 * MCP templates import as disabled server definitions, never auto-enabled.
 */
@Service
public class StoreService {

    private static final Logger log = LoggerFactory.getLogger(StoreService.class);

    private final StoreClient client;
    private final StoreInstallLedger ledger;
    private final PluginPackageService plugins;
    private final SkillPackageService skills;
    private final McpRuntimeManager mcp;
    private final Path runtimeRoot;
    private final String fallbackHostVersion;

    public StoreService(StoreClient client, StoreInstallLedger ledger,
            PluginPackageService plugins, SkillPackageService skills, McpRuntimeManager mcp,
            @Value("#{T(fan.summer.fengyu.runtime.RuntimePaths).root()}") Path runtimeRoot,
            @Value("${fengyu.store.host-version:4.1.0}") String fallbackHostVersion) {
        this.client = client;
        this.ledger = ledger;
        this.plugins = plugins;
        this.skills = skills;
        this.mcp = mcp;
        this.runtimeRoot = runtimeRoot;
        this.fallbackHostVersion = fallbackHostVersion;
    }

    // ---- catalog views ----

    /** Store API base as configured (surfaced by /api/store/status). */
    public String catalogApiBase() {
        return client.apiBase();
    }

    /**
     * Catalog merged with local install state. Flat on purpose: the SPA renders
     * these rows directly, so every catalog field is top-level alongside
     * installedVersion/installed.
     */
    public List<CatalogView> catalog(String type, String query)
            throws IOException, InterruptedException {
        CatalogPage page = client.browse(type, query, null, 60);
        List<CatalogView> view = new ArrayList<>();
        for (CatalogItem item : page.items()) {
            Optional<StoreInstallLedger.Entry> installed =
                    ledger.find(item.coordinate());
            view.add(new CatalogView(
                    item.coordinate(),
                    item.type(),
                    item.namespace(),
                    item.slug(),
                    item.name(),
                    item.summary(),
                    item.category(),
                    item.latestVersion(),
                    item.channel(),
                    item.publisherName(),
                    item.updatedAt(),
                    installed.map(StoreInstallLedger.Entry::version).orElse(null),
                    installed.isPresent()));
        }
        return view;
    }

    public ListingDetail listing(String namespace, String slug)
            throws IOException, InterruptedException {
        return client.listing(namespace, slug);
    }

    public List<InstalledView> installed() {
        List<InstalledView> out = new ArrayList<>();
        for (StoreInstallLedger.Entry entry : ledger.all()) {
            // The disk/runtime is the truth; the ledger only binds the coordinate.
            String actualVersion = switch (entry.type()) {
                case "PLUGIN" -> plugins.find(entry.localId())
                        .map(m -> m.version()).orElse(null);
                case "SKILL" -> skills.find(entry.localId())
                        .map(m -> m.version()).orElse(null);
                default -> mcpFilePresent(entry) ? entry.version() : null;
            };
            out.add(new InstalledView(entry.coordinate(), entry.type(), entry.localId(),
                    actualVersion != null ? actualVersion : entry.version(),
                    actualVersion != null));
        }
        return out;
    }

    /** Update check: one resolution round per installed coordinate. */
    public List<UpdateView> updates() throws IOException, InterruptedException {
        Map<String, String> installedMap = new LinkedHashMap<>();
        for (StoreInstallLedger.Entry entry : ledger.all()) {
            installedMap.put(entry.coordinate(), entry.version());
        }
        List<UpdateView> out = new ArrayList<>();
        for (StoreInstallLedger.Entry entry : ledger.all()) {
            try {
                ResolveResponse resolved = client.resolve(entry.coordinate(),
                        hostVersion(), os(), arch(), installedMap);
                ResolutionItem root = rootItem(resolved, entry.coordinate());
                if (root != null && root.version() != null
                        && isNewer(root.version(), entry.version())) {
                    out.add(new UpdateView(entry.coordinate(), entry.type(),
                            entry.version(), root.version(),
                            root.permissions() == null ? List.of()
                                    : root.permissions()));
                }
            } catch (IOException | InterruptedException e) {
                log.debug("Update check failed for {}: {}", entry.coordinate(), e.toString());
            }
        }
        return out;
    }

    // ---- install / uninstall ----

    /**
     * Installs (or updates) a store listing. The store resolves compatibility and
     * dependency closure first; the downloaded artifact is verified against the
     * store-attested SHA-256 before the type installer runs.
     */
    public InstallResult install(String coordinate, boolean confirmPermissions)
            throws IOException, InterruptedException {
        String type = coordinateType(coordinate);
        Map<String, String> installedMap = new LinkedHashMap<>();
        ledger.all().forEach(e -> installedMap.put(e.coordinate(), e.version()));

        ResolveResponse resolved = client.resolve(coordinate, hostVersion(), os(), arch(),
                installedMap);
        if (!resolved.resolvable()) {
            List<String> missing = resolved.missing() == null ? List.of()
                    : resolved.missing().stream().map(m -> m.coordinate()
                            + (m.range() == null ? "" : "@" + m.range())).toList();
            throw new IllegalArgumentException(
                    "Cannot install " + coordinate + " (host " + hostVersion()
                            + "): missing or incompatible dependencies " + missing);
        }
        ResolutionItem root = rootItem(resolved, coordinate);
        if (root == null) {
            throw new IllegalArgumentException("Store returned no plan for " + coordinate);
        }

        DownloadTicket ticket = client.ticket(root.releaseId());
        String localId;
        switch (type) {
            case "PLUGIN" -> {
                Path archive = client.download(ticket, ".fyp");
                try {
                    var manifest = plugins.install(archive, confirmPermissions);
                    localId = manifest.id();
                } finally {
                    Files.deleteIfExists(archive);
                }
            }
            case "SKILL" -> {
                Path archive = client.download(ticket, ".fys");
                try {
                    var manifest = skills.install(archive);
                    localId = manifest.id();
                } finally {
                    Files.deleteIfExists(archive);
                }
            }
            case "MCP" -> localId = importMcp(coordinate, ticket);
            default -> throw new IllegalArgumentException(
                    "Store installs of type " + type + " are not supported by this host yet");
        }

        ledger.record(coordinate, type, localId, root.version(), ticket.sha256());
        return new InstallResult(coordinate, type, localId, root.version(),
                root.permissions() == null ? List.of() : root.permissions(),
                resolved.plan() == null ? List.of() : resolved.plan().stream()
                        .filter(p -> p.coordinate() != null
                                && !p.coordinate().equals(coordinate))
                        .map(p -> p.coordinate()).toList());
    }

    public void uninstall(String coordinate, boolean deleteData) throws IOException {
        Optional<StoreInstallLedger.Entry> entry = ledger.find(coordinate);
        if (entry.isEmpty()) {
            throw new IllegalArgumentException("Not installed from the store: " + coordinate);
        }
        switch (entry.get().type()) {
            case "PLUGIN" -> plugins.uninstall(entry.get().localId(), deleteData);
            case "SKILL" -> skills.uninstall(entry.get().localId());
            case "MCP" -> {
                Path file = mcpFile(entry.get());
                Files.deleteIfExists(file);
                mcp.syncImportedServers();
            }
            default -> { /* ledger-only entry */ }
        }
        ledger.remove(coordinate);
    }

    // ---- internals ----

    /**
     * MCP templates import as a disabled server definition under mcp-servers/
     * (design §6.4 / ADR-004: installing a template never connects anything).
     */
    private String importMcp(String coordinate, DownloadTicket ticket)
            throws IOException, InterruptedException {
        JsonNode template = client.parseMcpTemplate(client.downloadBytes(ticket));
        ObjectNode servers = client.mapper().createObjectNode();
        ObjectNode server = client.mapper().createObjectNode();
        String url = template.path("urlTemplate").asText(null);
        String transport = template.path("transport").asText("STREAMABLE_HTTP");
        if (url == null || "STDIO".equals(transport)) {
            throw new IllegalArgumentException(
                    "MCP template declares no remote endpoint; refusing to import");
        }
        server.put("url", url);
        JsonNode secrets = template.path("requiredSecrets");
        if (secrets.isArray() && !secrets.isEmpty()) {
            ObjectNode headers = client.mapper().createObjectNode();
            for (JsonNode secret : secrets) {
                headers.put(secret.path("name").asText("authorization"), "REQUIRED_SECRET");
            }
            server.set("headers", headers);
        }
        String serverKey = coordinate.replace("infinia://", "").replace('/', '.');
        servers.set(serverKey, server);

        Path dir = fan.summer.fengyu.runtime.RuntimePaths.mcpDirectory(runtimeRoot);
        Files.createDirectories(dir);
        Path file = dir.resolve("store-" + safe(serverKey) + ".json");
        Files.writeString(file, client.mapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(servers), StandardCharsets.UTF_8);
        mcp.syncImportedServers();
        return serverKey;
    }

    private Path mcpFile(StoreInstallLedger.Entry entry) {
        return fan.summer.fengyu.runtime.RuntimePaths.mcpDirectory(runtimeRoot)
                .resolve("store-" + safe(entry.localId()) + ".json");
    }

    private boolean mcpFilePresent(StoreInstallLedger.Entry entry) {
        return Files.isRegularFile(mcpFile(entry));
    }

    private static String safe(String id) {
        return id.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static ResolutionItem rootItem(ResolveResponse resolved, String coordinate) {
        if (resolved.plan() == null || resolved.plan().isEmpty()) {
            return null;
        }
        return resolved.plan().stream()
                .filter(p -> coordinate.equals(p.coordinate()))
                .findFirst().orElse(resolved.plan().get(0));
    }

    static String coordinateType(String coordinate) {
        // infinia://<type>/<namespace>/<slug>[@version]
        String rest = coordinate.replace("infinia://", "");
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            throw new IllegalArgumentException("Invalid store coordinate: " + coordinate);
        }
        return rest.substring(0, slash).toUpperCase(Locale.ROOT);
    }

    private String hostVersion() {
        String current = PluginHostVersion.current();
        // Unresolvable dev strings fall back to the configured representative
        // version; genuine releases are reported truthfully so compatibility
        // checking stays honest (a prerelease build may legitimately not match).
        if (!fan.summer.fengyu.plugin.market.SemanticVersion.isValid(current)) {
            return fallbackHostVersion;
        }
        return current;
    }

    private static String os() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return "windows";
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return "macos";
        }
        return "linux";
    }

    private static String arch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.contains("aarch64") || arch.contains("arm") ? "arm64" : "x64";
    }

    /** Simple MAJOR.MINOR.PATCH comparison (sufficient for update flags). */
    private static boolean isNewer(String candidate, String installed) {
        long[] a = numeric(candidate);
        long[] b = numeric(installed);
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return a[i] > b[i];
            }
        }
        return false;
    }

    private static long[] numeric(String version) {
        long[] out = new long[3];
        if (version == null) {
            return out;
        }
        String[] parts = version.split("[-+]]")[0].split("\\.");
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                out[i] = Long.parseLong(parts[i].replaceAll("\\D", ""));
            } catch (NumberFormatException ignored) {
                // non-numeric segment counts as 0
            }
        }
        return out;
    }

    // ---- view DTOs ----

    /** Flat catalog row: catalog fields + local install state. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record CatalogView(
            String coordinate,
            String type,
            String namespace,
            String slug,
            String name,
            String summary,
            String category,
            String latestVersion,
            String channel,
            String publisherName,
            String updatedAt,
            String installedVersion,
            boolean installed) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record InstalledView(String coordinate, String type, String localId,
            String version, boolean present) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record UpdateView(String coordinate, String type, String installedVersion,
            String availableVersion, List<StoreModels.PermissionRef> permissions) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record InstallResult(String coordinate, String type, String localId,
            String version, List<StoreModels.PermissionRef> permissions,
            List<String> dependenciesInstalled) {}
}
