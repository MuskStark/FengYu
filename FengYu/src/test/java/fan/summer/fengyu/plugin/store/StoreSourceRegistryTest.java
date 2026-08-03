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
            List.of(new FengYuCatalogAdapter(), new ClaudeMarketplaceAdapter(), new CodexMarketplaceAdapter()),
            600);

        StoreSource added = registry.addSource("FengYu", StoreSourceType.FENGYU,
            "https://example.com/catalog.json");
        assertEquals("fengyu-fengyu", added.origin()); // origin = normalizeOrigin("FengYu", FENGYU)
        assertEquals(1, registry.listSources().size());
        assertTrue(repo.existsByOrigin("fengyu-fengyu"));
    }

    @Test
    void duplicateOriginIsRejected() {
        StoreSourceRegistry registry = new StoreSourceRegistry(repo,
            List.of(new FengYuCatalogAdapter()), 600);
        registry.addSource("FengYu", StoreSourceType.FENGYU, "https://example.com/a.json");
        assertThrows(IllegalStateException.class,
            () -> registry.addSource("FengYu", StoreSourceType.FENGYU, "https://example.com/b.json"));
    }
}
