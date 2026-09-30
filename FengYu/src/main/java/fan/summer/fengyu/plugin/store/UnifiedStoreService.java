package fan.summer.fengyu.plugin.store;

import fan.summer.fengyu.database.SecurityConstants;
import fan.summer.fengyu.plugin.market.ManifestI18n;
import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.market.SemanticVersion;
import fan.summer.fengyu.database.repository.PluginInstallRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/** Aggregates all marketplace sources into one unified catalog with install-state merge + filtering. */
@Service
public class UnifiedStoreService {
    private static final Logger log = LoggerFactory.getLogger(UnifiedStoreService.class);
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
        List<UnifiedCatalogEntry> aggregated = new ArrayList<>();
        for (StoreSource src : registry.listSources()) {
            aggregated.addAll(registry.fetchCatalog(src.origin()));
        }
        // Slug-collision disambiguation: two catalog names that slugify identically
        // ("My Plugin" vs "my-plugin") used to share one uid, silently overwriting each other's
        // install record. The FIRST entry (deterministic source/parse order) keeps the plain uid —
        // existing records keep matching it — and every later colliding entry gets a short stable
        // hash suffix derived from its distinguishing content, so both stay addressable.
        List<UnifiedCatalogEntry> all = new ArrayList<>(aggregated.size());
        Set<String> seenUids = new HashSet<>();
        for (UnifiedCatalogEntry e : aggregated) {
            if (seenUids.add(e.uid())) {
                all.add(e);
                continue;
            }
            String suffix = shortHash(e.displayName() + "|" + String.valueOf(e.sourceRef()));
            UnifiedCatalogEntry renamed = withUid(e, e.uid() + "-" + suffix);
            log.warn("Unified catalog uid collision: re-keyed '{}' entry as {} "
                + "(slugified names collide in source {})", e.displayName(), renamed.uid(), e.origin());
            all.add(renamed);
            seenUids.add(renamed.uid());
        }

        // 2. Load local install state: agent-content + FENGYU install records, then .fyp manifests.
        Map<String, Installed> installedByUid = new HashMap<>();
        Map<String, String> fengyuRecordUidToId = new HashMap<>();
        for (var rec : records.findAllByUserIdOrderByInstalledAtDesc(SecurityConstants.LOCAL_VIRTUAL_USER_ID)) {
            installedByUid.put(rec.getUid(), new Installed(rec.getVersion(), rec.isEnabled(), rec.getSourceType()));
            // A FENGYU record remembers the REAL installed plugin id (its install path directory),
            // which is what lets an entry whose catalog id differs from the package id still
            // uninstall the right plugin (P2-13).
            if (StoreSourceType.FENGYU.name().equals(rec.getSourceType())
                    && rec.getInstallPath() != null) {
                Path dir = Path.of(rec.getInstallPath()).getFileName();
                if (dir != null) fengyuRecordUidToId.put(rec.getUid(), dir.toString());
            }
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
            if (uid == null) {
                // P2-13: a FENGYU install whose package id differs from the catalog slug is bound
                // to its entry through the install record instead of the manifest-id index.
                uid = fengyuRecordUidToId.entrySet().stream()
                    .filter(entry -> m.id().equals(entry.getValue()))
                    .map(Map.Entry::getKey)
                    .findFirst().orElse(null);
            }
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
                    e.interfaceMeta(), true, inst.version, update, inst.enabled,
                    e.permissionsOsEnforced());
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

    /** Copies an entry under a new uid (slug-collision disambiguation only; all else identical). */
    private static UnifiedCatalogEntry withUid(UnifiedCatalogEntry e, String uid) {
        return new UnifiedCatalogEntry(uid, e.origin(), e.sourceType(), e.name(),
            e.displayName(), e.description(), e.author(), e.category(), e.keywords(),
            e.homepage(), e.pinnedSha(), e.availableVersion(), e.sha256(), e.signature(),
            e.keyId(), e.sourceRef(), e.declaredSkills(), e.mcpServers(), e.interfaceMeta(),
            e.installed(), e.installedVersion(), e.updateAvailable(), e.enabled(),
            e.permissionsOsEnforced());
    }

    /** Stable 6-hex-char digest used to disambiguate colliding uids without long path segments. */
    private static String shortHash(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash).substring(0, 6);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
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
