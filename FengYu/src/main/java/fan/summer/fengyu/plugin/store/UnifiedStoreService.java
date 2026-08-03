package fan.summer.fengyu.plugin.store;

import fan.summer.fengyu.database.SecurityConstants;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.database.repository.PluginInstallRecordRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/** Aggregates all marketplace sources into one unified catalog with install-state merge + filtering. */
@Service
public class UnifiedStoreService {
    private final StoreSourceRegistry registry;
    private final PluginInstallRecordRepository records;
    private final PluginPackageService packages;

    public UnifiedStoreService(StoreSourceRegistry registry,
            PluginInstallRecordRepository records, PluginPackageService packages) {
        this.registry = registry;
        this.records = records;
        this.packages = packages;
    }

    /** Filter params for {@link #list(StoreFilter)}. */
    public record StoreFilter(StoreSourceType sourceType, String category, String query) {}

    public List<UnifiedCatalogEntry> list(StoreFilter filter) {
        // 1. Aggregate remote catalogs from all enabled sources.
        List<UnifiedCatalogEntry> all = new ArrayList<>();
        for (StoreSource src : registry.listSources()) {
            all.addAll(registry.fetchCatalog(src.origin()));
        }

        // 2. Load local install state: agent-content records + .fyp manifests.
        Map<String, Installed> installedByUid = new HashMap<>();
        for (var rec : records.findAllByUserIdOrderByInstalledAtDesc(SecurityConstants.LOCAL_VIRTUAL_USER_ID)) {
            installedByUid.put(rec.getUid(), new Installed(rec.getVersion(), rec.isEnabled(), rec.getSourceType()));
        }
        // .fyp manifests don't store which origin they were installed from, so we cannot reconstruct
        // their full uid (<origin>:FENGYU:<id>). Instead, build an index of manifestId -> uid for
        // every FENGYU catalog entry already aggregated above, then for each installed manifest mark
        // the matching entry installed. Agent-content records (already in installedByUid) win, so we
        // only put when absent. isEnabled() is non-throwing; it just checks the .disabled marker.
        Map<String, String> fengyuManifestIdToUid = new HashMap<>();
        for (UnifiedCatalogEntry e : all) {
            if (e.sourceType() == StoreSourceType.FENGYU && e.name() != null) {
                fengyuManifestIdToUid.putIfAbsent(e.name(), e.uid());
            }
        }
        for (var m : packages.installed()) {
            if (m.id() == null) continue;
            String uid = fengyuManifestIdToUid.get(m.id());
            if (uid == null) continue;
            installedByUid.putIfAbsent(uid,
                new Installed(m.version(), packages.isEnabled(m.id()), StoreSourceType.FENGYU.name()));
        }

        // 3. Merge install state into entries.
        List<UnifiedCatalogEntry> merged = all.stream()
            .map(e -> {
                Installed inst = installedByUid.get(e.uid());
                if (inst == null) return e;
                boolean update = inst.version != null && e.installedVersion() != null
                    && compareVersions(e.installedVersion(), inst.version) > 0;
                return new UnifiedCatalogEntry(e.uid(), e.origin(), e.sourceType(), e.name(),
                    e.displayName(), e.description(), e.author(), e.category(), e.keywords(),
                    e.homepage(), e.pinnedSha(), e.sourceRef(), e.declaredSkills(), e.mcpServers(),
                    e.interfaceMeta(), true, inst.version, update, inst.enabled);
            })
            .collect(Collectors.toCollection(ArrayList::new));

        // 4. Filter.
        return merged.stream()
            .filter(e -> filter.sourceType() == null || e.sourceType() == filter.sourceType())
            .filter(e -> filter.category() == null || filter.category().isBlank()
                || filter.category().equalsIgnoreCase(e.category()))
            .filter(e -> filter.query() == null || filter.query().isBlank() || matchesQuery(e, filter.query()))
            .sorted(Comparator.comparing(UnifiedCatalogEntry::displayName, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    private static boolean matchesQuery(UnifiedCatalogEntry e, String q) {
        String ql = q.toLowerCase(Locale.ROOT);
        if (e.name() != null && e.name().toLowerCase(Locale.ROOT).contains(ql)) return true;
        if (e.description() != null && e.description().toLowerCase(Locale.ROOT).contains(ql)) return true;
        return e.keywords().stream().anyMatch(k -> k.toLowerCase(Locale.ROOT).contains(ql));
    }

    /** Best-effort 3-part numeric version compare (mirrors PluginMarketplaceService.compareVersions). */
    static int compareVersions(String left, String right) {
        int[] a = numeric(left);
        int[] b = numeric(right);
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(a[i], b[i]);
            if (c != 0) return c;
        }
        return 0;
    }

    private static int[] numeric(String v) {
        int[] out = new int[3];
        if (v == null) return out;
        String[] parts = v.split("[-+]", 2)[0].split("\\.");
        for (int i = 0; i < Math.min(parts.length, 3); i++) {
            try { out[i] = Integer.parseInt(parts[i]); } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    private record Installed(String version, boolean enabled, String sourceType) {}
}
