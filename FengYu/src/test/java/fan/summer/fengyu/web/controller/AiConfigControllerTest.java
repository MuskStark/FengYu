package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.AiConfigService;
import fan.summer.fengyu.ai.service.AiConfigServiceHeadless;
import fan.summer.fengyu.ai.service.AiModeService;
import fan.summer.fengyu.FengYuApplication;
import fan.summer.fengyu.ai.service.BackendReactivator;
import fan.summer.fengyu.ai.skill.SkillPackageService;
import fan.summer.fengyu.ai.skill.SkillRegistry;
import fan.summer.fengyu.database.repository.AppSettingRepository;
import fan.summer.fengyu.security.NoopSecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link AiConfigController}'s GET masked snapshot, PUT partial write +
 * hot-swap, and the {@code maskKey} helper. Uses the same {@code @DataJpaTest} +
 * test-profile pattern as {@code AiConfigServiceHeadlessTest}, with a real
 * {@link AppSettingRepository} so the static facade's writes round-trip through
 * the DB. The {@link BackendReactivator} is constructed with the real
 * {@link AiConfigService}; on the default "local" mode {@code reactivate()} works
 * without a Spring context (Task 3 proved this), so PUT never throws.
 */
@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = FengYuApplication.class)
class AiConfigControllerTest {

    @Autowired private AppSettingRepository repo;

    private AiConfigController controller;

    @BeforeEach
    void setUp() throws Exception {
        var sc = new NoopSecurityContext();
        AiConfigService cfg = new AiConfigService(repo, sc);
        cfg.init();
        AiConfigServiceHeadless h = new AiConfigServiceHeadless(repo, sc, cfg);
        // AiConfigServiceHeadless.init() is package-private (its @PostConstruct); this test lives in
        // web.controller, so invoke it reflectively to publish the static singleton.
        var initMethod = AiConfigServiceHeadless.class.getDeclaredMethod("init");
        initMethod.setAccessible(true);
        initMethod.invoke(h);
        AiModeService ms = new AiModeService();
        // SkillPackageService points at an empty temp dir so no skills are discovered; matches
        // the production wiring (BackendReactivator injects the registry into backends). The
        // registry now wraps the package service (skills are managed like plugins).
        SkillPackageService skillPackages = new SkillPackageService(
                System.getProperty("java.io.tmpdir") + "/fengyu-skills-test");
        SkillRegistry skills = new SkillRegistry(skillPackages);
        // BackendReactivator with the real AiConfigService; local mode works without Spring context.
        BackendReactivator reactivator = new BackendReactivator(ms, new ToolCallback[0], skills, cfg);
        controller = new AiConfigController(ms, reactivator);
    }

    @Test
    void get_returnsDefaults_withMaskedKeys() {
        Map<String, Object> result = controller.get();
        assertEquals("local", result.get("mode"));
        Map<?, ?> openai = (Map<?, ?>) result.get("openai");
        assertEquals("", openai.get("apiKey"));
        assertEquals(false, openai.get("apiKeySet"));
        assertEquals("gpt-4o", openai.get("model"));
        assertEquals("local", result.get("activeMode"));
    }

    @Test
    void get_masksApiKey_whenSet() {
        AiConfigServiceHeadless.setAiOpenAiApiKey("sk-1234567890abcdef");
        Map<String, Object> result = controller.get();
        Map<?, ?> openai = (Map<?, ?>) result.get("openai");
        assertEquals(true, openai.get("apiKeySet"));
        assertEquals("sk-1***cdef", openai.get("apiKey"));
    }

    @Test
    void put_skipsApiKeyPlaceholder() {
        AiConfigServiceHeadless.setAiOpenAiApiKey("sk-realkey123456");
        controller.put(Map.of("openai", Map.of("apiKey", "sk-r***456")));
        assertEquals("sk-realkey123456", AiConfigService.getAiOpenAiApiKey());
    }

    @Test
    void put_emptyApiKey_preservesExistingKey() {
        AiConfigServiceHeadless.setAiOpenAiApiKey("sk-realkey123456");
        controller.put(Map.of("openai", Map.of("apiKey", "")));
        assertEquals("sk-realkey123456", AiConfigService.getAiOpenAiApiKey());
    }

    @Test
    void put_writesNewApiKey() {
        controller.put(Map.of("openai", Map.of("apiKey", "sk-brandnewkey99")));
        assertEquals("sk-brandnewkey99", AiConfigService.getAiOpenAiApiKey());
    }

    @Test
    void put_writesMode_andReturnsSnapshot() {
        Map<String, Object> result = controller.put(Map.of("mode", "local"));
        assertEquals("local", result.get("mode"));
        assertEquals("local", AiConfigService.getAiMode());
    }

    @Test
    void put_writesSamplingParams() {
        controller.put(Map.of("temperature", 0.5, "topP", 0.8, "maxTokens", 1024));
        assertEquals(0.5f, AiConfigService.getAiTemperature(), 0.001);
        assertEquals(0.8f, AiConfigService.getAiTopP(), 0.001);
        assertEquals(1024, AiConfigService.getAiMaxTokens());
    }

    @Test
    void put_writesOllamaSettings() {
        controller.put(Map.of("ollama", Map.of("baseUrl", "http://gpu:11434", "model", "llama3:8b")));
        assertEquals("http://gpu:11434", AiConfigService.getAiOllamaBaseUrl());
        assertEquals("llama3:8b", AiConfigService.getAiOllamaModel());
    }

    @Test
    void maskKey_shortKey_returnsPrefixWithStars() {
        assertEquals("", AiConfigController.maskKey(""));
        assertEquals("sk-1***", AiConfigController.maskKey("sk-1"));
        assertEquals("sk-1***cdef", AiConfigController.maskKey("sk-1234567890abcdef"));
    }
}
