package fan.summer.fengyu.plugin.store;

import fan.summer.fengyu.database.SecurityConstants;
import fan.summer.fengyu.plugin.market.ManifestI18n;
import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.market.SemanticVersion;
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
        return list(filter, null);
    }

    /**
     * Aggregate the unified catalog, optionally localizing installed entries' display name and
     * description. Catalog-only entries (not installed) keep the catalog's strings — the catalog
     * format carries a single language, so only an installed manifest provides translations (via its
     * {@code i18n} block). A {@code null} locale leaves installed entries' strings untouched too
     * (used by the install-lifecycle lookup, which never displays them).
     */
    public List<UnifiedCatalogEntry> list(StoreFilter filter, String locale) {
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
        // Index installed FENGYU manifests by uid so the merge can localize their display strings
        // from the manifest's i18n block — the catalog itself carries only one language, so an
        // installed plugin's localized name/description was previously lost in this path.
        Map<String, PluginManifest> manifestByUid = new HashMap<>();
        for (var m : packages.installed()) {
            if (m.id() == null) continue;
            String uid = fengyuManifestIdToUid.get(m.id());
            if (uid == null) continue;
            installedByUid.putIfAbsent(uid,
                new Installed(m.version(), packages.isEnabled(m.id()), StoreSourceType.FENGYU.name()));
            manifestByUid.putIfAbsent(uid, m);
        }

        // 3. Merge install state into entries; localize installed entries when a locale is given.
        List<UnifiedCatalogEntry> merged = all.stream()
            .map(e -> {
                Installed inst = installedByUid.get(e.uid());
                if (inst == null) return e;
                boolean update = inst.version != null && SemanticVersion.isValid(inst.version)
                    && e.availableVersion() != null && SemanticVersion.isValid(e.availableVersion())
                    && SemanticVersion.compare(e.availableVersion(), inst.version) > 0;
                PluginManifest m = manifestByUid.get(e.uid());
                String displayName = (m != null && locale != null)
                    ? ManifestI18n.name(m, locale) : e.displayName();
                String description = (m != null && locale != null)
                    ? ManifestI18n.description(m, locale) : e.description();
                return new UnifiedCatalogEntry(e.uid(), e.origin(), e.sourceType(), e.name(),
                    displayName, description, e.author(), e.category(), e.keywords(),
                    e.homepage(), e.pinnedSha(), e.availableVersion(), e.sha256(),
                    e.signature(), e.keyId(), e.sourceRef(), e.declaredSkills(), e.mcpServers(),
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

    /** Compatibility entry point retained for package-local tests and callers. */
    static int compareVersions(String left, String right) {
        return SemanticVersion.compare(left, right);
    }

    private record Installed(String version, boolean enabled, String sourceType) {}
}
