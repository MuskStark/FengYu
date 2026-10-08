package fan.summer.fengyu.web.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.database.SecurityConstants;
import fan.summer.fengyu.database.entity.store.PluginInstallRecordEntity;
import fan.summer.fengyu.database.repository.PluginInstallRecordRepository;
import fan.summer.fengyu.plugin.market.ManifestI18n;
import fan.summer.fengyu.plugin.store.InstallerDispatcher;
import fan.summer.fengyu.plugin.store.StoreSource;
import fan.summer.fengyu.plugin.store.StoreSourceRegistry;
import fan.summer.fengyu.plugin.store.StoreSourceType;
import fan.summer.fengyu.plugin.store.UnifiedCatalogEntry;
import fan.summer.fengyu.plugin.store.UnifiedStoreService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Unified plugin store API: the official Infinia store's catalog (aggregated server-side)
 *  plus install lifecycle and install history. */
@RestController
@RequestMapping("/api/plugin-store")
public class PluginStoreController {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final StoreSourceRegistry sources;
    private final UnifiedStoreService store;
    private final InstallerDispatcher dispatcher;
    private final PluginInstallRecordRepository records;
    /** Reused to parse the JSON-string columns on install records into typed lists. */
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    public PluginStoreController(StoreSourceRegistry sources, UnifiedStoreService store,
            InstallerDispatcher dispatcher, PluginInstallRecordRepository records) {
        this.sources = sources;
        this.store = store;
        this.dispatcher = dispatcher;
        this.records = records;
    }

    // ── sources ──────────────────────────────────────────────
    @GetMapping("/sources")
    public List<StoreSource> listSources() { return sources.listSources(); }

    @PostMapping("/sources")
    public ResponseEntity<StoreSource> addSource(@RequestBody AddSourceRequest req) {
        // Third-party marketplace integration was retired: the official Infinia store
        // aggregates that content server-side, so FENGYU is the only subscribable type.
        if (!StoreSourceType.FENGYU.name().equals(req.sourceType())) {
            throw new IllegalArgumentException(
                "Only FENGYU sources are supported; the official store aggregates the rest");
        }
        StoreSource src = sources.addSource(req.name(), StoreSourceType.FENGYU, req.catalogUrl());
        return ResponseEntity.status(HttpStatus.CREATED).body(src);
    }

    @DeleteMapping("/sources/{origin}")
    public void deleteSource(@PathVariable String origin) { sources.deleteSource(origin); }

    @PostMapping("/sources/{origin}/refresh")
    public void refreshSource(@PathVariable String origin) { sources.refresh(origin); }

    // ── catalog ──────────────────────────────────────────────
    @GetMapping("/catalog")
    public List<UnifiedCatalogEntry> catalog(
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q,
            @RequestHeader(name = "Accept-Language", required = false) String acceptLanguage) {
        StoreSourceType st = sourceType == null ? null : StoreSourceType.valueOf(sourceType);
        // Resolve the request locale so installed entries render their localized name/description
        // from the on-disk manifest's i18n block (catalog-only entries carry a single language).
        String locale = ManifestI18n.resolveLocale(acceptLanguage);
        return store.list(new UnifiedStoreService.StoreFilter(st, category, q), locale);
    }

    // ── install lifecycle ────────────────────────────────────
    @PostMapping("/{uid}/install")
    public void install(@PathVariable String uid) {
        UnifiedCatalogEntry entry = findEntry(uid);
        dispatcher.install(entry);
    }

    @PostMapping("/{uid}/update")
    public void update(@PathVariable String uid,
            @RequestParam(name = "confirmPermissions", defaultValue = "false") boolean confirmPermissions) {
        UnifiedCatalogEntry entry = findEntry(uid);
        dispatcher.update(entry, confirmPermissions);
    }

    @DeleteMapping("/{uid}")
    public void uninstall(@PathVariable String uid,
            @RequestParam(name = "deleteData") boolean deleteData) {
        UnifiedCatalogEntry entry = findEntryOrNull(uid);
        if (entry != null) {
            dispatcher.uninstall(entry, deleteData);
            return;
        }
        // 4.0 third-party installs (Claude/Codex/Grok) have no catalog adapter anymore —
        // the install record is the only remaining handle, and users must still be able
        // to remove that content.
        dispatcher.uninstallLegacyThirdParty(uid,
                fan.summer.fengyu.runtime.RuntimePaths.root());
    }

    @PatchMapping("/{uid}/enabled")
    public void setEnabled(@PathVariable String uid, @RequestBody EnabledRequest req) {
        UnifiedCatalogEntry entry = findEntry(uid);
        dispatcher.setEnabled(entry, req.enabled());
    }

    // ── history ──────────────────────────────────────────────
    @GetMapping("/history")
    public List<InstallRecordView> history() {
        return records.findAllByUserIdOrderByInstalledAtDesc(SecurityConstants.LOCAL_VIRTUAL_USER_ID)
            .stream()
            .map(this::toView)
            .toList();
    }

    // ── helpers ──────────────────────────────────────────────
    private UnifiedCatalogEntry findEntry(String uid) {
        UnifiedCatalogEntry entry = findEntryOrNull(uid);
        if (entry == null) {
            throw new IllegalArgumentException("No catalog entry for uid: " + uid);
        }
        return entry;
    }

    private UnifiedCatalogEntry findEntryOrNull(String uid) {
        // One catalog pull indexed by uid — a map lookup instead of a per-request linear
        // scan, with duplicate uids resolved deterministically to the first entry (the same
        // wins rule UnifiedStoreService applies when it dedupes colliding uids).
        Map<String, UnifiedCatalogEntry> byUid = new HashMap<>();
        for (UnifiedCatalogEntry entry : store.list(new UnifiedStoreService.StoreFilter(null, null, null))) {
            byUid.putIfAbsent(entry.uid(), entry);
        }
        return byUid.get(uid);
    }

    /** Maps a raw entity into the clean history view, parsing the JSON-string columns. */
    private InstallRecordView toView(PluginInstallRecordEntity e) {
        return new InstallRecordView(
            e.getUid(), e.getPluginName(), e.getSourceType(), e.getOrigin(), e.getVersion(), e.getPinnedSha(),
            e.isHasMcpServers(), e.isEnabled(),
            parseStringList(e.getDeclaredSkills()),
            parseStringList(e.getMcpServerRefs()),
            iso(e.getInstalledAt()), iso(e.getUpdatedAt())
        );
    }

    /** Parses a JSON array string (e.g. {@code ["a","b"]}) into a list; null/empty/invalid → empty list. */
    private List<String> parseStringList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            List<String> parsed = json.readValue(raw, STRING_LIST);
            return parsed == null ? List.of() : parsed;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static String iso(LocalDateTime t) {
        return t == null ? null : ISO.format(t);
    }

    public record AddSourceRequest(String name, String sourceType, String catalogUrl) {}
    public record EnabledRequest(boolean enabled) {}

    /**
     * Clean install-record view for {@code GET /history}: parses the JSON-string columns
     * ({@code declaredSkills}, {@code mcpServerRefs}) into typed lists, ISO-formats the
     * timestamps, and excludes internal fields ({@code id}, {@code userId}, {@code installPath}).
     */
    public record InstallRecordView(
            String uid,
            String pluginName,
            String sourceType,
            String origin,
            String version,
            String pinnedSha,
            boolean hasMcpServers,
            boolean enabled,
            List<String> declaredSkills,
            List<String> mcpServerRefs,
            String installedAt,
            String updatedAt) {}
}
