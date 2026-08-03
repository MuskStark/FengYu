package fan.summer.fengyu.database.repository;

import fan.summer.fengyu.FengYuApplication;
import fan.summer.fengyu.database.SecurityConstants;
import fan.summer.fengyu.database.entity.store.StoreSourceEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = FengYuApplication.class)
class StoreSourceRepositoryTest {
    @Autowired private StoreSourceRepository repo;

    @Test
    void findByOrigin_returnsSeededSource() {
        StoreSourceEntity e = new StoreSourceEntity();
        e.setOrigin("anthropics-claude");
        e.setName("Anthropic");
        e.setSourceType("CLAUDE");
        e.setCatalogUrl("https://example.com/m.json");
        e.setEnabled(true);
        e.setUserId(SecurityConstants.LOCAL_VIRTUAL_USER_ID);
        repo.save(e);

        Optional<StoreSourceEntity> found = repo.findByOrigin("anthropics-claude");
        assertTrue(found.isPresent());
        assertEquals("Anthropic", found.get().getName());
    }
}
