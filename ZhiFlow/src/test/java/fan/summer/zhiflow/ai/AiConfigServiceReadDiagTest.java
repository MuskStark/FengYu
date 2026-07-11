package fan.summer.zhiflow.ai;

import fan.summer.zhiflow.database.entity.AppSettingEntity;
import fan.summer.zhiflow.database.repository.AppSettingRepository;
import fan.summer.zhiflow.security.NoopSecurityContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DIAGNOSTIC test: exercises the EXACT read path AiConfigService uses at startup
 * (NoopSecurityContext → user 1 → findByUserIdAndSettingKey) against a DB seeded
 * with a real key, to confirm the key round-trips. This isolates whether the
 * startup "no credential source" failure is a read-path bug vs a construction bug.
 *
 * <p>Uses the repo's working @DataJpaTest setup (H2 test profile).
 */
@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = fan.summer.zhiflow.ai.spring.AiApplication.class)
class AiConfigServiceReadDiagTest {

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
        System.out.println("DIAG deepseek key len=" + key.length() + " head=" + key.substring(0, 6));
        assertTrue(key.startsWith("sk-1234567890abcdefDIAG"),
                "read path must return the seeded key; got: " + key);
    }
}
