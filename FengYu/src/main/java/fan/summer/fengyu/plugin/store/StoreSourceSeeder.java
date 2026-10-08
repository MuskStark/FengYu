package fan.summer.fengyu.plugin.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds the default FengYu store source on startup so the unified store browses the official
 * Infinia store out of the box. The source's {@code catalogUrl} is normally blank — a blank
 * URL makes {@link FengYuCatalogAdapter} read the official store catalog through the shared
 * store client ({@code fengyu.store.api-base}); {@code fengyu.marketplace.catalog-url} opts
 * the default source into a legacy self-hosted JSON-array catalog instead. Idempotent, so
 * existing installs pick the source up on their next start.
 *
 * @since 4.0.0
 */
@Component
public class StoreSourceSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StoreSourceSeeder.class);
    private final StoreSourceRegistry registry;
    private final String catalogUrl;

    public StoreSourceSeeder(StoreSourceRegistry registry,
            @Value("${fengyu.marketplace.catalog-url:}") String catalogUrl) {
        this.registry = registry;
        this.catalogUrl = catalogUrl == null ? "" : catalogUrl.trim();
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    public synchronized void seed() {
        try {
            registry.addSource("FengYu Default", StoreSourceType.FENGYU, catalogUrl);
            log.info("Seeded default FengYu store source ({}), catalog via {}",
                    catalogUrl.isBlank() ? "official store" : catalogUrl,
                    catalogUrl.isBlank() ? "fengyu.store.api-base" : "catalog-url");
        } catch (IllegalStateException already) {
            // already seeded — fine
        } catch (IllegalArgumentException policy) {
            // A configured legacy catalog URL that violates the egress posture (e.g. an intranet
            // catalog while allow-private-network is off) must not abort host startup: skip
            // seeding and say why. The source can be subscribed later once the posture allows it.
            log.warn("Default FengYu store source not seeded: {} (catalog-url {})",
                    policy.getMessage(), catalogUrl);
        }
    }
}
