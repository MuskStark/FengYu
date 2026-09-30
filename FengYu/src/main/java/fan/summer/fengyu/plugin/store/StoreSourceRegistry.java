package fan.summer.fengyu.plugin.store;

import fan.summer.fengyu.database.SecurityConstants;
import fan.summer.fengyu.database.entity.store.StoreSourceEntity;
import fan.summer.fengyu.database.repository.StoreSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Manages subscribed marketplace sources: CRUD, adapter dispatch, and TTL caching of fetches. */
@Service
public class StoreSourceRegistry {
    private static final Logger log = LoggerFactory.getLogger(StoreSourceRegistry.class);

    private final StoreSourceRepository repo;
    private final Map<StoreSourceType, MarketplaceSourceAdapter> adapters;
    private final long ttlSeconds;

    // cache: origin -> (entries, fetchedAtMillis)
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public StoreSourceRegistry(StoreSourceRepository repo,
            List<MarketplaceSourceAdapter> adapterList,
            @Value("${fengyu.store.cache-ttl-seconds:600}") long ttlSeconds) {
        this.repo = repo;
        this.ttlSeconds = ttlSeconds;
        Map<StoreSourceType, MarketplaceSourceAdapter> m = new EnumMap<>(StoreSourceType.class);
        for (MarketplaceSourceAdapter a : adapterList) m.put(a.type(), a);
        this.adapters = Map.copyOf(m);
    }

    public List<StoreSource> listSources() {
        return repo.findAllByUserId(SecurityConstants.LOCAL_VIRTUAL_USER_ID).stream()
            .map(StoreSourceRegistry::toView).toList();
    }

    public StoreSource addSource(String name, StoreSourceType type, String catalogUrl) {
        String origin = normalizeOrigin(name, type);
        if (repo.existsByOrigin(origin)) {
            throw new IllegalStateException("Source already subscribed: " + origin);
        }
        StoreSourceEntity e = new StoreSourceEntity();
        e.setOrigin(origin);
        e.setName(name);
        e.setSourceType(type.name());
        e.setCatalogUrl(catalogUrl);
        e.setEnabled(true);
        e.setUserId(SecurityConstants.LOCAL_VIRTUAL_USER_ID);
        repo.save(e);
        return toView(e);
    }

    public void deleteSource(String origin) {
        repo.deleteByOrigin(origin);
        cache.remove(origin);
    }

    public void refresh(String origin) {
        cache.remove(origin);
    }

    /** Fetches the catalog for one source, using the TTL cache. Returns empty list on failure. */
    public List<UnifiedCatalogEntry> fetchCatalog(String origin) {
        StoreSourceEntity e = repo.findByOrigin(origin)
            .orElseThrow(() -> new IllegalArgumentException("Unknown source: " + origin));
        if (!e.isEnabled()) return List.of();

        CacheEntry hit = cache.get(origin);
        long now = System.currentTimeMillis();
        if (hit != null && (now - hit.fetchedAt) < ttlSeconds * 1000L) return hit.entries;

        StoreSource view = toView(e);
        MarketplaceSourceAdapter adapter = adapters.get(view.sourceType());
        try {
            List<UnifiedCatalogEntry> entries = adapter.fetchCatalog(view);
            cache.put(origin, new CacheEntry(entries, now));
            markSync(e, true, null);
            return entries;
        } catch (RuntimeException ex) {
            log.warn("Fetch failed for source {}: {}", origin, ex.getMessage());
            markSync(e, false, ex.getMessage());
            return List.of();
        }
    }

    private void markSync(StoreSourceEntity e, boolean ok, String err) {
        e.setLastSyncAt(LocalDateTime.now());
        e.setLastSyncOk(ok);
        e.setLastError(ok ? null : truncate(err, 3800));
        repo.save(e);
    }

    private static String truncate(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }

    static String normalizeOrigin(String name, StoreSourceType type) {
        String slug = name.toLowerCase(Locale.ROOT).trim()
            .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (slug.isEmpty()) slug = "source";
        return slug + "-" + type.name().toLowerCase();
    }

    static StoreSource toView(StoreSourceEntity e) {
        return new StoreSource(e.getOrigin(), StoreSourceType.valueOf(e.getSourceType()),
            e.getCatalogUrl(), e.getName());
    }

    private record CacheEntry(List<UnifiedCatalogEntry> entries, long fetchedAt) {}
}
