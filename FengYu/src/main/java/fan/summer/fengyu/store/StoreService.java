package fan.summer.fengyu.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fan.summer.fengyu.ai.mcp.McpRuntimeManager;
import fan.summer.fengyu.ai.skill.SkillPackageService;
import fan.summer.fengyu.plugin.market.PluginHostVersion;
import fan.summer.fengyu.plugin.market.PluginLifecycleOrchestrator;
import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.market.SemanticVersion;
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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Install orchestrator for the Infinia Store (design §9): resolve → ticketed
 * download → SHA-256 verify → type-specific installer, wrapped in one
 * crash-recoverable transaction. The complete resolution plan executes
 * dependency-first (review M-2); plugin installs/updates run through {@link
 * PluginLifecycleOrchestrator} so the runtime gate, health preflight and
 * package commit/rollback can never be bypassed (M-3); every mutation is
 * journaled and rolls back in reverse order on failure or restart (M-8).
 * Plugins and skills reuse the host's own package services (which re-validate
 * manifests and permissions); MCP templates import as disabled server
 * definitions, never auto-enabled.
 */
@Service
public class StoreService {

    private static final Logger log = LoggerFactory.getLogger(StoreService.class);

    private final StoreClient client;
    private final StoreInstallLedger ledger;
    private final PluginPackageService plugins;
    private final PluginLifecycleOrchestrator pluginLifecycle;
    private final SkillPackageService skills;
    private final McpRuntimeManager mcp;
    private final Path runtimeRoot;
    private final String fallbackHostVersion;
    /** One store transaction at a time: the journal file is a singleton. */
    private final ReentrantLock transactionLock = new ReentrantLock();

    public StoreService(StoreClient client, StoreInstallLedger ledger,
            PluginPackageService plugins, PluginLifecycleOrchestrator pluginLifecycle,
            SkillPackageService skills, McpRuntimeManager mcp,
            @Value("#{T(fan.summer.fengyu.runtime.RuntimePaths).root()}") Path runtimeRoot,
            @Value("${fengyu.store.host-version:4.1.0}") String fallbackHostVersion) {
        this.client = client;
        this.ledger = ledger;
        this.plugins = plugins;
        this.pluginLifecycle = pluginLifecycle;
        this.skills = skills;
        this.mcp = mcp;
        this.runtimeRoot = runtimeRoot;
        this.fallbackHostVersion = fallbackHostVersion;
        recoverInterruptedTransaction();
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
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                log.debug("Update check failed for {}: {}", entry.coordinate(), e.toString());
            }
        }
        return out;
    }

    // ---- install / uninstall ----

    /**
     * Installs (or updates) a store listing as ONE journaled transaction: the
     * store resolves compatibility and dependency closure, then the complete
     * plan is executed dependency-first with each artifact verified against the
     * store-attested SHA-256 before its type installer runs. The ledger is only
     * bound after every installer and plugin health preflight succeeded, and a
     * failure rolls applied items back in reverse order.
     */
    public InstallResult install(String coordinate, boolean confirmPermissions)
            throws IOException, InterruptedException {
        String type = coordinateType(coordinate);
        transactionLock.lock();
        try {
            Map<String, String> installedMap = new LinkedHashMap<>();
            ledger.all().forEach(e -> installedMap.put(e.coordinate(), e.version()));

            ResolveResponse resolved = client.resolve(coordinate, hostVersion(), os(),
                    arch(), installedMap);
            if (!resolved.resolvable()) {
                List<String> missing = resolved.missing() == null ? List.of()
                        : resolved.missing().stream().map(m -> m.coordinate()
                                + (m.range() == null ? "" : "@" + m.range())
                                + (m.reason() == null ? "" : " (" + m.reason() + ")"))
                                .toList();
                throw new IllegalArgumentException(
                        "Cannot install " + coordinate + " (host " + hostVersion()
                                + "): missing or incompatible dependencies " + missing);
            }
            ResolutionItem root = rootItem(resolved, coordinate);
            if (root == null) {
                throw new IllegalArgumentException("Store returned no plan for " + coordinate);
            }

            StoreInstallJournal journal = StoreInstallJournal.begin(storeDir(), coordinate,
                    journalItems(resolved.plan(), coordinate));
            try {
                for (StoreInstallJournal.ItemState item : journal.items()) {
                    DownloadTicket ticket = client.ticket(item.releaseId());
                    switch (item.type()) {
                        case "PLUGIN" -> applyPluginItem(journal, item, ticket,
                                confirmPermissions);
                        case "SKILL" -> applySkillItem(journal, item, ticket);
                        case "MCP" -> applyMcpItem(journal, item, ticket);
                        default -> throw new IllegalArgumentException(
                                "Store installs of type " + item.type()
                                        + " are not supported by this host yet");
                    }
                    journal.noteTicketSha(item.coordinate(), ticket.sha256());
                }

                // Commit: bind every applied coordinate in the ledger with one save …
                String now = Instant.now().toString();
                List<StoreInstallLedger.Entry> entries = new ArrayList<>();
                for (StoreInstallJournal.ItemState item : journal.items()) {
                    if (!item.applied()) {
                        continue;
                    }
                    entries.add(new StoreInstallLedger.Entry(item.coordinate(),
                            item.type(), item.localId(), item.version(), item.sha256(),
                            now));
                }
                ledger.recordAll(entries);

                // … and only then release the plugin rollback snapshots.
                List<String> commitFailures = new ArrayList<>();
                for (StoreInstallJournal.ItemState item : journal.items()) {
                    if (item.applied() && "PLUGIN".equals(item.type())
                            && !item.committed()) {
                        try {
                            pluginLifecycle.commitStaged(item.localId());
                            journal.markCommitted(item.coordinate());
                        } catch (IOException | RuntimeException commitFailure) {
                            log.error("Plugin {} failed to commit after a successful "
                                    + "install: {}", item.localId(), commitFailure);
                            commitFailures.add(item.coordinate());
                        }
                    }
                }
                if (!commitFailures.isEmpty()) {
                    throw new IOException("Store transaction commit failed for "
                            + commitFailures);
                }

                String rootLocalId = journal.item(coordinate).localId();
                // Report what THIS transaction actually installed (M-2): the
                // already-satisfied dependencies the store skipped are not ours.
                List<String> dependenciesInstalled = journal.items().stream()
                        .filter(i -> i.applied() && !coordinate.equals(i.coordinate()))
                        .map(StoreInstallJournal.ItemState::coordinate).toList();
                journal.delete();
                return new InstallResult(coordinate, type, rootLocalId, root.version(),
                        root.permissions() == null ? List.of() : root.permissions(),
                        dependenciesInstalled);
            } catch (InterruptedException interrupted) {
                rollbackTransaction(journal, false);
                throw interrupted;
            } catch (IOException | RuntimeException failure) {
                rollbackTransaction(journal, false);
                throw failure;
            }
        } finally {
            transactionLock.unlock();
        }
    }

    public void uninstall(String coordinate, boolean deleteData) throws IOException {
        transactionLock.lock();
        try {
            Optional<StoreInstallLedger.Entry> entry = ledger.find(coordinate);
            if (entry.isEmpty()) {
                throw new IllegalArgumentException("Not installed from the store: " + coordinate);
            }
            StoreInstallLedger.Entry e = entry.get();
            switch (e.type()) {
                case "PLUGIN" -> pluginLifecycle.uninstallWithGate(e.localId(), deleteData);
                case "SKILL" -> skills.uninstall(e.localId());
                case "MCP" -> {
                    Files.deleteIfExists(mcpFile(e));
                    mcp.syncImportedServers();
                }
                default -> { /* ledger-only entry */ }
            }
            ledger.remove(coordinate);
        } finally {
            transactionLock.unlock();
        }
    }

    // ---- transaction internals ----

    /**
     * Journal seeds for the plan: dependencies first, the requested root last;
     * dependencies the store marked already-satisfied are skipped, the root
     * always runs (an explicit install on the current version is a reinstall).
     */
    private List<StoreInstallJournal.ItemState> journalItems(List<ResolutionItem> plan,
            String coordinate) {
        List<StoreInstallJournal.ItemState> items = new ArrayList<>();
        for (ResolutionItem planItem : executionOrder(plan, coordinate)) {
            if (planItem.alreadyInstalled() && !coordinate.equals(planItem.coordinate())) {
                continue;
            }
            items.add(new StoreInstallJournal.ItemState(planItem.coordinate(),
                    coordinateType(planItem.coordinate()), planItem.releaseId(),
                    planItem.version(), null, null, false, false,
                    ledger.find(planItem.coordinate()).orElse(null), null, null));
        }
        return items;
    }

    /** Dependencies before the root, whatever order the store listed them in. */
    private static List<ResolutionItem> executionOrder(List<ResolutionItem> plan,
            String rootCoordinate) {
        if (plan == null || plan.isEmpty()) {
            return List.of();
        }
        List<ResolutionItem> order = new ArrayList<>();
        for (ResolutionItem item : plan) {
            if (item != null && item.coordinate() != null
                    && !rootCoordinate.equals(item.coordinate())) {
                order.add(item);
            }
        }
        for (ResolutionItem item : plan) {
            if (item != null && rootCoordinate.equals(item.coordinate())) {
                order.add(item);
                break;
            }
        }
        return order;
    }

    private void applyPluginItem(StoreInstallJournal journal,
            StoreInstallJournal.ItemState item, DownloadTicket ticket,
            boolean confirmPermissions) throws IOException, InterruptedException {
        Path archive = client.download(ticket, ".fyp");
        try {
            PluginManifest incoming = plugins.readArchiveManifest(archive);
            String id = incoming.id();
            boolean update = pluginLifecycle.isInstalled(id);
            pluginLifecycle.beginStaged(id);
            boolean swapped = false;
            try {
                PluginManifest manifest = plugins.install(archive, confirmPermissions);
                swapped = true;
                if (update) {
                    pluginLifecycle.preflightStaged(id);
                }
                journal.markApplied(item.coordinate(), manifest.id());
            } catch (IOException | RuntimeException failure) {
                closeGate(id, update, swapped);
                throw failure;
            }
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    /** Failure path: a swapped update restores its predecessor; otherwise just reopen the gate. */
    private void closeGate(String id, boolean update, boolean swapped) {
        if (update && swapped) {
            pluginLifecycle.rollbackStaged(id);
        } else {
            pluginLifecycle.endStaged(id);
        }
    }

    private void applySkillItem(StoreInstallJournal journal,
            StoreInstallJournal.ItemState item, DownloadTicket ticket)
            throws IOException, InterruptedException {
        Path archive = client.download(ticket, ".fys");
        try {
            // Skills keep no retained package-level rollback: snapshot a replaced
            // skill directory before the swap so the transaction can restore it.
            StoreInstallLedger.Entry old = item.oldLedgerEntry();
            if (old != null) {
                Path existing = skills.root().resolve(old.localId());
                if (Files.isDirectory(existing)) {
                    Files.createDirectories(journal.backupDir());
                    String backupName = "skill-" + safe(old.localId());
                    copyTree(existing, journal.backupDir().resolve(backupName));
                    journal.noteSkillBackup(item.coordinate(), backupName);
                }
            }
            var manifest = skills.install(archive);
            journal.markApplied(item.coordinate(), manifest.id());
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    /**
     * MCP templates import as a disabled server definition under mcp-servers/
     * (design §6.4 / ADR-004: installing a template never connects anything).
     */
    private void applyMcpItem(StoreInstallJournal journal,
            StoreInstallJournal.ItemState item, DownloadTicket ticket)
            throws IOException, InterruptedException {
        String serverKey = serverKey(item.coordinate());
        Path file = mcpFileByKey(serverKey);
        journal.noteMcpOld(item.coordinate(), Files.isRegularFile(file)
                ? Base64.getEncoder().encodeToString(Files.readAllBytes(file)) : null);

        JsonNode template = client.parseMcpTemplate(client.downloadBytes(ticket));
        ObjectNode servers = client.mapper().createObjectNode();
        servers.set(serverKey, buildMcpServer(template));
        Files.createDirectories(file.getParent());
        Files.writeString(file, client.mapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(servers), StandardCharsets.UTF_8);
        mcp.syncImportedServers();
        journal.markApplied(item.coordinate(), serverKey);
    }

    private ObjectNode buildMcpServer(JsonNode template) {
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
        return server;
    }

    // ---- rollback & restart recovery ----

    /**
     * Startup recovery: a journal file left behind means the JVM died mid-plan;
     * applied items are rolled back exactly like a runtime failure (updates via
     * the package service's retained snapshot, fresh installs removed, skills
     * and MCP state restored from the journal's backups).
     */
    private void recoverInterruptedTransaction() {
        var leftover = StoreInstallJournal.load(storeDir());
        if (leftover.isEmpty()) {
            return;
        }
        StoreInstallJournal.PendingTransaction tx = leftover.get();
        log.warn("Recovering interrupted store transaction {} (root {}): rolling back "
                + "its applied items", tx.id(), tx.rootCoordinate());
        rollbackTransaction(StoreInstallJournal.attach(storeDir(), tx), true);
    }

    private void rollbackTransaction(StoreInstallJournal journal, boolean startup) {
        List<StoreInstallJournal.ItemState> applied = new ArrayList<>(journal.items());
        applied.removeIf(i -> !i.applied());
        Collections.reverse(applied);
        for (StoreInstallJournal.ItemState item : applied) {
            try {
                rollbackItem(item, journal, startup);
            } catch (Exception e) {
                log.error("Could not roll back store item {}: {}", item.coordinate(),
                        e.toString());
            }
        }
        journal.delete();
    }

    private void rollbackItem(StoreInstallJournal.ItemState item,
            StoreInstallJournal journal, boolean startup) {
        switch (item.type()) {
            case "PLUGIN" -> rollbackPluginItem(item, startup);
            case "SKILL" -> rollbackSkillItem(item, journal);
            case "MCP" -> rollbackMcpItem(item);
            default -> { }
        }
        ledger.restore(item.coordinate(), item.oldLedgerEntry());
    }

    private void rollbackPluginItem(StoreInstallJournal.ItemState item, boolean startup) {
        String id = item.localId();
        boolean wasUpdate = item.oldLedgerEntry() != null
                && id.equals(item.oldLedgerEntry().localId());
        if (item.committed()) {
            if (wasUpdate) {
                log.error("Store update of {} was already committed and cannot be "
                        + "rolled back automatically; reinstall the previous version "
                        + "from the store", item.coordinate());
            } else {
                removePluginQuietly(id, startup);
            }
            return;
        }
        if (startup) {
            // The package service's own startup recovery has usually restored an
            // update already; a leftover attempt is harmless. Fresh orphans are
            // removed directly — no invoke can race a bean constructor.
            if (wasUpdate) {
                try {
                    plugins.rollbackUpdate(id);
                } catch (Exception alreadyRecovered) {
                    // see above
                }
            } else {
                removePluginQuietly(id, true);
            }
        } else if (wasUpdate) {
            pluginLifecycle.rollbackStaged(id);
        } else {
            removePluginQuietly(id, false);
        }
    }

    private void removePluginQuietly(String id, boolean startup) {
        try {
            if (startup) {
                plugins.uninstall(id, false);
            } else {
                pluginLifecycle.uninstallWithGate(id, false);
            }
        } catch (Exception e) {
            log.warn("Could not remove partially installed plugin {}: {}", id, e.toString());
        }
    }

    private void rollbackSkillItem(StoreInstallJournal.ItemState item,
            StoreInstallJournal journal) {
        try {
            skills.uninstall(item.localId());
        } catch (IOException | RuntimeException notInstalled) {
            // The swap may have failed before the new version published.
        }
        if (item.skillBackup() != null && item.oldLedgerEntry() != null) {
            Path backup = journal.backupDir().resolve(item.skillBackup());
            if (Files.isDirectory(backup)) {
                try {
                    Path target = skills.root().resolve(item.oldLedgerEntry().localId());
                    Files.createDirectories(target.getParent());
                    Files.move(backup, target);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
    }

    private void rollbackMcpItem(StoreInstallJournal.ItemState item) {
        Path file = mcpFileByKey(item.localId());
        try {
            if (item.mcpOldContent() != null) {
                Files.createDirectories(file.getParent());
                Files.write(file, Base64.getDecoder().decode(item.mcpOldContent()));
            } else {
                Files.deleteIfExists(file);
            }
            mcp.syncImportedServers();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path to = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(to);
                } else {
                    Files.createDirectories(to.getParent());
                    Files.copy(path, to, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    // ---- helpers ----

    private Path storeDir() {
        return runtimeRoot.resolve("store");
    }

    private static String serverKey(String coordinate) {
        return coordinate.replace("infinia://", "").replace('/', '.');
    }

    private Path mcpFileByKey(String serverKey) {
        return fan.summer.fengyu.runtime.RuntimePaths.mcpDirectory(runtimeRoot)
                .resolve("store-" + safe(serverKey) + ".json");
    }

    private Path mcpFile(StoreInstallLedger.Entry entry) {
        return mcpFileByKey(entry.localId());
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
        if (!SemanticVersion.isValid(current)) {
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

    /**
     * SemVer-aware update flag (M-1): prerelease precedence must be honored so
     * beta.5 users see rc.1 and then 4.0.0, beta.10 outranks beta.2, and build
     * metadata never marks an update. Non-SemVer store data degrades to an
     * exact-string change check.
     */
    static boolean isNewer(String candidate, String installed) {
        if (candidate == null || installed == null || candidate.equals(installed)) {
            return false;
        }
        try {
            return SemanticVersion.compare(candidate, installed) > 0;
        } catch (IllegalArgumentException notSemVer) {
            return true;
        }
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
