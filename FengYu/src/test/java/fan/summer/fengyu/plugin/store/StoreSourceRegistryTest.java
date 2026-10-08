package fan.summer.fengyu.plugin.store;

import fan.summer.fengyu.FengYuApplication;
import fan.summer.fengyu.database.repository.StoreSourceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = FengYuApplication.class)
class StoreSourceRegistryTest {

    // Public IP literals keep the egress-policy checks hermetic (no live DNS — see UrlPolicyTest).
    private static final String PUBLIC_CATALOG = "https://93.184.216.34/catalog.json";

    @Autowired private StoreSourceRepository repo;

    @Test
    void listsAndPersistsSources() {
        StoreSourceRegistry registry = new StoreSourceRegistry(repo,
            List.of(new FengYuCatalogAdapter(null)),
            600);

        StoreSource added = registry.addSource("FengYu", StoreSourceType.FENGYU,
            PUBLIC_CATALOG);
        assertEquals("fengyu-fengyu", added.origin()); // origin = normalizeOrigin("FengYu", FENGYU)
        // The boot seeder registers the official default source in this full-application
        // context, so count assertions are brittle — assert memberships instead.
        assertTrue(registry.listSources().stream()
            .anyMatch(s -> "fengyu-fengyu".equals(s.origin())));
        assertTrue(repo.existsByOrigin("fengyu-fengyu"));
        assertTrue(repo.existsByOrigin("fengyu-default-fengyu"),
            "the official default source is seeded");
    }

    @Test
    void duplicateOriginIsRejected() {
        StoreSourceRegistry registry = new StoreSourceRegistry(repo,
            List.of(new FengYuCatalogAdapter(null)), 600);
        registry.addSource("FengYu", StoreSourceType.FENGYU, PUBLIC_CATALOG);
        assertThrows(IllegalStateException.class,
            () -> registry.addSource("FengYu", StoreSourceType.FENGYU, "https://93.184.216.34/b.json"));
    }

    /**
     * P2 (catalogUrl SSRF gap): a subscribed catalog URL runs through the same egress policy as
     * store downloads — link-local metadata endpoints, intranet hosts, and plain-HTTP
     * non-loopback URLs are rejected at subscribe time with an actionable verdict. A blank URL
     * (the default official-store channel) stays exempt.
     */
    @Test
    void addSourceRejectsCatalogUrlsThatViolateTheEgressPolicy() {
        StoreSourceRegistry registry = new StoreSourceRegistry(repo,
            List.of(new FengYuCatalogAdapter(null)), 600);

        for (String intranet : new String[] {
                "http://169.254.169.254/latest/meta-data/",  // cloud metadata endpoint
                "https://192.168.1.5/catalog.json",           // intranet host
                "http://93.184.216.34/catalog.json"}) {       // plain HTTP off loopback
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> registry.addSource("Bad " + intranet, StoreSourceType.FENGYU, intranet),
                intranet);
            assertTrue(rejected.getMessage().contains("egress policy"),
                intranet + ": " + rejected.getMessage());
        }
        // A blank catalog URL (the official store channel) is not a remote URL — still addable.
        assertDoesNotThrow(() -> registry.addSource("Blank Ok", StoreSourceType.FENGYU, ""));
    }

    /**
     * Fail-safe (P1): a failed fetch serves the source's LAST-GOOD entries and is negative-cached
     * for the short failure backoff — an unreachable store must not turn every catalog list into
     * a fresh network attempt plus a sync-row write. The public API keeps returning entries (the
     * stale catalog stays visible) until the backoff expires and the fetch is retried.
     */
    @Test
    void fetchFailureServesLastGoodEntriesAndIsNegativeCachedUntilTheBackoffExpires() {
        AtomicInteger attempts = new AtomicInteger();
        MarketplaceSourceAdapter flaky = new MarketplaceSourceAdapter() {
            volatile boolean fail;
            @Override public StoreSourceType type() { return StoreSourceType.FENGYU; }
            @Override public List<UnifiedCatalogEntry> fetchCatalog(StoreSource src) {
                if (attempts.incrementAndGet() == 1) {
                    return List.of(entry("last-good"));
                }
                throw new IllegalStateException("store unreachable");
            }
        };
        // ttlSeconds=0: the success TTL never applies, so every non-backoff call refetches.
        StoreSourceRegistry registry = new StoreSourceRegistry(repo, List.of(flaky), 0);
        String origin = registry.addSource("Flaky", StoreSourceType.FENGYU, PUBLIC_CATALOG).origin();

        assertEquals(1, registry.fetchCatalog(origin).size(), "the first fetch succeeds");
        // The store goes down: the failed attempt still serves the last-good entry …
        assertEquals(1, registry.fetchCatalog(origin).size(),
            "a failed fetch serves the last-good entries");
        assertEquals(2, attempts.get(), "exactly one fetch attempt for the failure");
        // … and the negative-cache window serves it again WITHOUT another network attempt.
        assertEquals(1, registry.fetchCatalog(origin).size());
        assertEquals(1, registry.fetchCatalog(origin).size());
        assertEquals(2, attempts.get(), "the backoff window must not re-attempt the fetch");

        // Backoff expired (seam to 0): the next call retries the network again.
        long originalBackoff = StoreSourceRegistry.failureBackoffMillis;
        StoreSourceRegistry.failureBackoffMillis = 0;
        try {
            assertEquals(1, registry.fetchCatalog(origin).size(),
                "after the backoff the last-good entries are still served on failure");
            assertEquals(3, attempts.get(), "the expired backoff retries the fetch");
        } finally {
            StoreSourceRegistry.failureBackoffMillis = originalBackoff;
        }
    }

    /** No last-good snapshot exists yet: the failure surfaces as an empty list (as before). */
    @Test
    void fetchFailureWithoutHistoryServesAnEmptyListUntilTheBackoffExpires() {
        AtomicInteger attempts = new AtomicInteger();
        MarketplaceSourceAdapter dead = new MarketplaceSourceAdapter() {
            @Override public StoreSourceType type() { return StoreSourceType.FENGYU; }
            @Override public List<UnifiedCatalogEntry> fetchCatalog(StoreSource src) {
                attempts.incrementAndGet();
                throw new IllegalStateException("store unreachable");
            }
        };
        StoreSourceRegistry registry = new StoreSourceRegistry(repo, List.of(dead), 600);
        String origin = registry.addSource("Dead", StoreSourceType.FENGYU, PUBLIC_CATALOG).origin();

        assertTrue(registry.fetchCatalog(origin).isEmpty());
        assertTrue(registry.fetchCatalog(origin).isEmpty());
        assertEquals(1, attempts.get(), "the backoff window must not re-attempt the failed fetch");
    }

    /** A minimal catalog entry for the stub adapters above. */
    private static UnifiedCatalogEntry entry(String name) {
        return new UnifiedCatalogEntry("flaky-fengyu:" + name, "flaky-fengyu",
            StoreSourceType.FENGYU, name, name, "d", null, null, List.of(), null, null,
            new UnifiedCatalogEntry.ZipUrlSource("https://93.184.216.34/" + name + ".fyp"),
            List.of(), List.of(), null, false, null, false, false);
    }
}
