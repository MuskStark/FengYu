package fan.summer.fengyu.ai;

import fan.summer.fengyu.database.entity.AppSettingEntity;
import fan.summer.fengyu.database.repository.AppSettingRepository;
import fan.summer.fengyu.security.NoopSecurityContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the EXACT read path AiConfigService uses at startup
 * (NoopSecurityContext → user 1 → findByUserIdAndSettingKey) against a DB seeded
 * with a key, to confirm the key round-trips. This isolates whether the startup
 * "no credential source" failure is a read-path bug vs a construction bug.
 *
 * <p>The assertion must never echo the key material (CQ-06): neither a diagnostic
 * println nor the failure message may include the actual value.
 *
 * <p>Uses the repo's working @DataJpaTest setup (H2 test profile).
 */
@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = fan.summer.fengyu.FengYuApplication.class)
class AiConfigServiceReadPathTest {

    @Autowired AppSettingRepository repo;

    @Test
    void deepSeekKey_roundTripsThroughReadPath() {
        // Seed a key for user 1 — same shape as AiConfigController PUT writes.
        AppSettingEntity e = new AppSettingEntity();
        e.setUserId(1L);
        e.setSettingKey("ai.deepseek.api_key");
        e.setSettingValue("sk-1234567890abcdefDIAG");
        repo.save(e);

        AiConfigService svc = new AiConfigService(repo, new NoopSecurityContext());
        svc.init(); // publish singleton

        String key = AiConfigService.getAiDeepSeekApiKey();
        // No key material in the failure message — only that the read path diverged.
        assertTrue("sk-1234567890abcdefDIAG".equals(key),
                "read path must return the seeded key");
    }
}
