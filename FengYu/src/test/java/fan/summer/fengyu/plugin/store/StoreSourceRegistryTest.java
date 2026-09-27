package fan.summer.fengyu.plugin.store;

import fan.summer.fengyu.FengYuApplication;
import fan.summer.fengyu.database.repository.StoreSourceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = FengYuApplication.class)
class StoreSourceRegistryTest {

    @Autowired private StoreSourceRepository repo;

    @Test
    void listsAndPersistsSources() {
        StoreSourceRegistry registry = new StoreSourceRegistry(repo,
            List.of(new FengYuCatalogAdapter(null)),
            600);

        StoreSource added = registry.addSource("FengYu", StoreSourceType.FENGYU,
            "https://example.com/catalog.json");
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
        registry.addSource("FengYu", StoreSourceType.FENGYU, "https://example.com/a.json");
        assertThrows(IllegalStateException.class,
            () -> registry.addSource("FengYu", StoreSourceType.FENGYU, "https://example.com/b.json"));
    }
}
