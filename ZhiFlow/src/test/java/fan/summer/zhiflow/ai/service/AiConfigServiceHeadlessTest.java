package fan.summer.zhiflow.ai.service;

import fan.summer.zhiflow.ai.AiConfigService;
import fan.summer.zhiflow.ai.spring.AiApplication;
import fan.summer.zhiflow.database.SecurityConstants;
import fan.summer.zhiflow.database.entity.AppSettingEntity;
import fan.summer.zhiflow.database.repository.AppSettingRepository;
import fan.summer.zhiflow.security.NoopSecurityContext;
import fan.summer.zhiflow.security.SecurityContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the new provider setters round-trip through the DB and are readable
 * via {@link AiConfigService} (key constants must match exactly).
 *
 * <p>Mirrors {@code AppSettingRepositoryTest}'s {@code @DataJpaTest} + test-profile setup.
 * {@link AiConfigService} is a {@code @Component}; it is NOT instantiated by {@code @DataJpaTest}
 * (which only scans JPA repos + entities), so we construct it manually with the real repo + a
 * {@link NoopSecurityContext}, then trigger its {@code @PostConstruct init()} to publish the
 * static singleton that {@code AiConfigServiceHeadless} delegates to.
 */
@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = AiApplication.class)
class AiConfigServiceHeadlessTest {

    @Autowired private AppSettingRepository repo;

    private AiConfigServiceHeadless newHeadless() {
        SecurityContext sc = new NoopSecurityContext();
        AiConfigService cfg = new AiConfigService(repo, sc);
        cfg.init();  // publish static singleton
        AiConfigServiceHeadless h = new AiConfigServiceHeadless(repo, sc, cfg);
        h.init();    // publish static singleton
        return h;
    }

    @Test
    void setAiMode_roundTrips() {
        newHeadless().setAiMode("openai");
        assertEquals("openai", AiConfigService.getAiMode());
    }

    @Test
    void setAiOpenAiEndpoint_roundTrips() {
        newHeadless().setAiOpenAiEndpoint("https://my.proxy.com");
        assertEquals("https://my.proxy.com", AiConfigService.getAiOpenAiEndpoint());
    }

    @Test
    void setAiOpenAiApiKey_roundTrips() {
        newHeadless().setAiOpenAiApiKey("sk-secret-123");
        assertEquals("sk-secret-123", AiConfigService.getAiOpenAiApiKey());
    }

    @Test
    void setAiOpenAiModel_roundTrips() {
        newHeadless().setAiOpenAiModel("gpt-4o-mini");
        assertEquals("gpt-4o-mini", AiConfigService.getAiOpenAiModel());
    }

    @Test
    void setAiAnthropicApiKey_roundTrips() {
        newHeadless().setAiAnthropicApiKey("sk-ant-secret");
        assertEquals("sk-ant-secret", AiConfigService.getAiAnthropicApiKey());
    }

    @Test
    void setAiAnthropicModel_roundTrips() {
        newHeadless().setAiAnthropicModel("claude-opus-4");
        assertEquals("claude-opus-4", AiConfigService.getAiAnthropicModel());
    }

    @Test
    void setAiDeepSeekEndpoint_roundTrips() {
        newHeadless().setAiDeepSeekEndpoint("https://api.deepseek.com");
        assertEquals("https://api.deepseek.com", AiConfigService.getAiDeepSeekEndpoint());
    }

    @Test
    void setAiDeepSeekApiKey_roundTrips() {
        newHeadless().setAiDeepSeekApiKey("sk-ds-secret");
        assertEquals("sk-ds-secret", AiConfigService.getAiDeepSeekApiKey());
    }

    @Test
    void setAiOllamaBaseUrl_roundTrips() {
        newHeadless().setAiOllamaBaseUrl("http://gpu-box:11434");
        assertEquals("http://gpu-box:11434", AiConfigService.getAiOllamaBaseUrl());
    }

    @Test
    void setAiOllamaModel_roundTrips() {
        newHeadless().setAiOllamaModel("llama3:8b");
        assertEquals("llama3:8b", AiConfigService.getAiOllamaModel());
    }

    @Test
    void setters_updateExistingRow_notInsertDuplicate() {
        AiConfigServiceHeadless h = newHeadless();
        h.setAiMode("openai");
        h.setAiMode("anthropic");
        long count = repo.findAllByUserId(SecurityConstants.LOCAL_VIRTUAL_USER_ID).stream()
                .filter(e -> "ai.mode".equals(e.getSettingKey())).count();
        assertEquals(1, count);
        assertEquals("anthropic", AiConfigService.getAiMode());
    }
}
