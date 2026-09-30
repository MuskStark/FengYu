package fan.summer.fengyu.plugin.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds a default FengYu source on startup so the unified store works out of the box when
 * {@code fengyu.marketplace.catalog-url} is configured. Mirrors {@code OfficialPluginSeeder}.
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
        if (catalogUrl.isBlank()) return;
        try {
            registry.addSource("FengYu Default", StoreSourceType.FENGYU, catalogUrl);
            log.info("Seeded default FengYu store source ({})", catalogUrl);
        } catch (IllegalStateException already) {
            // already seeded — fine
        }
    }
}
