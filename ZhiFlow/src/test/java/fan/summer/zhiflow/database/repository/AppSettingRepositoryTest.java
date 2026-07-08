package fan.summer.zhiflow.database.repository;

import fan.summer.zhiflow.database.SecurityConstants;
import fan.summer.zhiflow.database.entity.AppSettingEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies AppSettingRepository persists and isolates settings per user_id.
 * Uses @DataJpaTest (in-memory H2, auto-create schema from entities).
 */
@DataJpaTest
class AppSettingRepositoryTest {

    @Autowired
    private AppSettingRepository repo;

    @Test
    void findByUserIdAndSettingKey_returnsValue_forVirtualUser() {
        AppSettingEntity e = new AppSettingEntity();
        e.setSettingKey("theme");
        e.setSettingValue("dark");
        e.setUserId(SecurityConstants.LOCAL_VIRTUAL_USER_ID);
        repo.save(e);

        Optional<AppSettingEntity> found =
                repo.findByUserIdAndSettingKey(SecurityConstants.LOCAL_VIRTUAL_USER_ID, "theme");

        assertTrue(found.isPresent());
        assertEquals("dark", found.get().getSettingValue());
    }

    @Test
    void findByUserIdAndSettingKey_isolatesUsers() {
        Long userA = 1L, userB = 2L;
        AppSettingEntity a = new AppSettingEntity();
        a.setSettingKey("theme"); a.setSettingValue("dark"); a.setUserId(userA);
        AppSettingEntity b = new AppSettingEntity();
        b.setSettingKey("theme"); b.setSettingValue("light"); b.setUserId(userB);
        repo.save(a);
        repo.save(b);

        assertEquals("dark",
            repo.findByUserIdAndSettingKey(userA, "theme").orElseThrow().getSettingValue());
        assertEquals("light",
            repo.findByUserIdAndSettingKey(userB, "theme").orElseThrow().getSettingValue());
    }
}
