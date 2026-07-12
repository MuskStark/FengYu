package fan.summer.fengyu.database.repository;

import fan.summer.fengyu.FengYuApplication;
import fan.summer.fengyu.database.SecurityConstants;
import fan.summer.fengyu.database.entity.AppSettingEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies AppSettingRepository persists and isolates settings per user_id.
 * Uses @DataJpaTest (in-memory H2, auto-create schema from entities).
 *
 * <p>Uses the {@code test} profile (see {@code application-test.yml}) for the H2 datasource + JPA
 * properties. Because this test lives in a subpackage where the upward
 * {@code @SpringBootConfiguration} search fails, the configuration is declared explicitly via
 * {@link ContextConfiguration} pointing at {@link FengYuApplication} (which carries
 * {@code @EntityScan} + {@code @EnableJpaRepositories} for the {@code database} subpackages).
 */
@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = FengYuApplication.class)
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
