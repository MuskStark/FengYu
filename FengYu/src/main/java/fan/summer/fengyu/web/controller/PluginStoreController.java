package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.database.SecurityConstants;
import fan.summer.fengyu.database.repository.PluginInstallRecordRepository;
import fan.summer.fengyu.plugin.store.InstallerDispatcher;
import fan.summer.fengyu.plugin.store.StoreSource;
import fan.summer.fengyu.plugin.store.StoreSourceRegistry;
import fan.summer.fengyu.plugin.store.StoreSourceType;
import fan.summer.fengyu.plugin.store.UnifiedCatalogEntry;
import fan.summer.fengyu.plugin.store.UnifiedStoreService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Unified plugin store API: aggregate multiple marketplaces (FengYu/Claude/Codex) + install. */
@RestController
@RequestMapping("/api/plugin-store")
public class PluginStoreController {
    private final StoreSourceRegistry sources;
    private final UnifiedStoreService store;
    private final InstallerDispatcher dispatcher;
    private final PluginInstallRecordRepository records;

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
            @RequestParam(required = false) String q) {
        StoreSourceType st = sourceType == null ? null : StoreSourceType.valueOf(sourceType);
        return store.list(new UnifiedStoreService.StoreFilter(st, category, q));
    }

    // ── install lifecycle ────────────────────────────────────
    @PostMapping("/{uid}/install")
    public void install(@PathVariable String uid) {
        UnifiedCatalogEntry entry = findEntry(uid);
        dispatcher.install(entry);
    }

    @PostMapping("/{uid}/update")
    public void update(@PathVariable String uid) {
        UnifiedCatalogEntry entry = findEntry(uid);
        dispatcher.update(entry);
    }

    @DeleteMapping("/{uid}")
    public void uninstall(@PathVariable String uid) {
        UnifiedCatalogEntry entry = findEntry(uid);
        dispatcher.uninstall(entry);
    }

    @PatchMapping("/{uid}/enabled")
    public void setEnabled(@PathVariable String uid, @RequestBody EnabledRequest req) {
        UnifiedCatalogEntry entry = findEntry(uid);
        dispatcher.setEnabled(entry, req.enabled());
    }

    // ── history ──────────────────────────────────────────────
    @GetMapping("/history")
    public List<?> history() {
        return records.findAllByUserIdOrderByInstalledAtDesc(SecurityConstants.LOCAL_VIRTUAL_USER_ID);
    }

    // ── helpers ──────────────────────────────────────────────
    private UnifiedCatalogEntry findEntry(String uid) {
        return store.list(new UnifiedStoreService.StoreFilter(null, null, null)).stream()
            .filter(e -> e.uid().equals(uid))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No catalog entry for uid: " + uid));
    }

    public record AddSourceRequest(String name, String sourceType, String catalogUrl) {}
    public record EnabledRequest(boolean enabled) {}
}
