package fan.summer.fengyu.plugin.store;

import fan.summer.fengyu.database.SecurityConstants;
import fan.summer.fengyu.database.entity.store.StoreSourceEntity;
import fan.summer.fengyu.database.repository.StoreSourceRepository;
import fan.summer.fengyu.store.UrlPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
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

    /**
     * Fail-safe backoff for a FAILED fetch: the failure (and the source's last-good entries, when
     * one exists) is cached for this window so an unreachable store cannot turn every catalog
     * list into a fresh network attempt plus a sync-row DB write. Deliberately SHORT (≤30s): the
     * e2e smoke boots a store stub that becomes healthy shortly after the host starts probing, so
     * a long failure window would keep serving the failure after the stub is up. Volatile test
     * seam, mirroring PluginProcessManager's.
     */
    static volatile long failureBackoffMillis = 15_000;

    // cache: origin -> (entries, fetchedAt, attempt state)
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * P2-10 egress posture shared with the store client and the plugin downloader: the launch
     * property OR the live Settings toggle, re-read per check. A third-party catalog URL must not
     * be aimed at link-local metadata endpoints or intranet hosts.
     */
    private volatile boolean launchAllowPrivateNetwork = false;

    public StoreSourceRegistry(StoreSourceRepository repo,
            List<MarketplaceSourceAdapter> adapterList,
            @Value("${fengyu.store.cache-ttl-seconds:600}") long ttlSeconds) {
        this.repo = repo;
        this.ttlSeconds = ttlSeconds;
        Map<StoreSourceType, MarketplaceSourceAdapter> m = new EnumMap<>(StoreSourceType.class);
        for (MarketplaceSourceAdapter a : adapterList) m.put(a.type(), a);
        this.adapters = Map.copyOf(m);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureEgressPosture(
            @org.springframework.beans.factory.annotation.Value("${fengyu.store.allow-private-network:false}") boolean allow) {
        this.launchAllowPrivateNetwork = allow;
    }

    private boolean egressPosture() {
        return launchAllowPrivateNetwork
                || fan.summer.fengyu.ai.service.AiConfigServiceHeadless.isStoreAllowPrivateNetwork();
    }

    public List<StoreSource> listSources() {
        return repo.findAllByUserId(SecurityConstants.LOCAL_VIRTUAL_USER_ID).stream()
            .map(StoreSourceRegistry::toView).toList();
    }

    public StoreSource addSource(String name, StoreSourceType type, String catalogUrl) {
        // Reject a policy-violating catalog URL at subscribe time (fail early, actionable message)
        // instead of recording a source whose every fetch is refused. A blank URL (the default
        // official-store channel, served through StoreClient's own policy) skips this check.
        if (catalogUrl != null && !catalogUrl.isBlank()) {
            URI uri = URI.create(catalogUrl);
            if (!List.of("https", "http").contains(uri.getScheme())) {
                throw new IllegalArgumentException("Catalog URL must use HTTP(S): " + catalogUrl);
            }
            try {
                UrlPolicy.requireTraversable(uri, egressPosture());
            } catch (IOException policy) {
                throw new IllegalArgumentException(
                    "Catalog URL rejected by the egress policy: " + policy.getMessage(), policy);
            }
        }
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

    /**
     * Fetches the catalog for one source, using the TTL cache. On failure the LAST-GOOD entries
     * (or an empty list when none exist) are served and the failure is negative-cached for the
     * short {@link #failureBackoffMillis} window — an unreachable store degrades to a stale
     * catalog instead of a fetch-storm of network attempts and sync-row writes.
     */
    public List<UnifiedCatalogEntry> fetchCatalog(String origin) {
        StoreSourceEntity e = repo.findByOrigin(origin)
            .orElseThrow(() -> new IllegalArgumentException("Unknown source: " + origin));
        if (!e.isEnabled()) return List.of();

        CacheEntry hit = cache.get(origin);
        long now = System.currentTimeMillis();
        if (hit != null && (now - hit.fetchedAt) < ttlSeconds * 1000L) return hit.entries();
        // Negative-cache window: the previous attempt failed recently — serve its result without
        // retrying the network or rewriting the sync row.
        if (hit != null && !hit.attemptOk() && (now - hit.attemptAt) < failureBackoffMillis) {
            return hit.entries();
        }

        StoreSource view = toView(e);
        MarketplaceSourceAdapter adapter = adapters.get(view.sourceType());
        if (adapter == null) {
            // Third-party marketplace adapters were retired; a legacy row of that type stays
            // listed but contributes nothing (and its error explains why). The empty result
            // is cached like any other, so a legacy row doesn't rewrite its sync row on
            // every catalog list.
            cache.put(origin, new CacheEntry(List.of(), now, now, true));
            markSync(e, false, "Source type " + view.sourceType()
                    + " is no longer integrated; the official Infinia store aggregates it");
            return List.of();
        }
        try {
            List<UnifiedCatalogEntry> entries = adapter.fetchCatalog(view);
            cache.put(origin, new CacheEntry(entries, now, now, true));
            markSync(e, true, null);
            return entries;
        } catch (RuntimeException ex) {
            log.warn("Fetch failed for source {}: {}", origin, ex.getMessage());
            // Keep the last-good entries (the stale catalog stays visible); only when none exist
            // does the failure surface as an empty list. Either way the failure is remembered for
            // the backoff window so callers polling the unified catalog do not hammer the store.
            cache.put(origin, new CacheEntry(hit != null ? hit.entries() : List.of(),
                hit != null ? hit.fetchedAt() : 0L, now, false));
            markSync(e, false, ex.getMessage());
            return cache.get(origin).entries();
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

    /**
     * @param entries    the entries to serve (last-good on failure)
     * @param fetchedAt  when {@code entries} were successfully fetched (0 when never)
     * @param attemptAt  when the last fetch attempt ran
     * @param attemptOk  whether that attempt succeeded
     */
    private record CacheEntry(List<UnifiedCatalogEntry> entries, long fetchedAt,
            long attemptAt, boolean attemptOk) {}
}
