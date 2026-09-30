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
import java.util.List;

/** Unified plugin store API: aggregate multiple marketplaces (FengYu/Claude/Codex/Grok) + install. */
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
        StoreSource src = sources.addSource(req.name(), StoreSourceType.valueOf(req.sourceType()), req.catalogUrl());
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
        UnifiedCatalogEntry entry = findEntry(uid);
        dispatcher.uninstall(entry, deleteData);
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
        return store.list(new UnifiedStoreService.StoreFilter(null, null, null)).stream()
            .filter(e -> e.uid().equals(uid))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No catalog entry for uid: " + uid));
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
