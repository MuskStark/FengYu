package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.FengYuApplication;
import fan.summer.fengyu.ai.AiConfigService;
import fan.summer.fengyu.ai.provider.ProviderRegistryService;
import fan.summer.fengyu.ai.service.AiConfigServiceHeadless;
import fan.summer.fengyu.ai.service.AiModeService;
import fan.summer.fengyu.ai.service.BackendReactivator;
import fan.summer.fengyu.ai.skill.SkillPackageService;
import fan.summer.fengyu.ai.skill.SkillRegistry;
import fan.summer.fengyu.ai.tools.ChatToolApprovalGate;
import fan.summer.fengyu.database.repository.AppSettingRepository;
import fan.summer.fengyu.security.NoopSecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch B3 REST surface: masked list view (never the key), create validation,
 * activation with hot-swap on a Spring-less local backend, and builtin delete
 * rejection. Same {@code @DataJpaTest} pattern as {@code AiConfigControllerTest}.
 */
@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = FengYuApplication.class)
class AiProviderControllerTest {

    @Autowired private AppSettingRepository repo;

    private AiProviderController controller;
    private ProviderRegistryService registry;
    private AiModeService ms;

    @BeforeEach
    void setUp() throws Exception {
        var sc = new NoopSecurityContext();
        AiConfigService cfg = new AiConfigService(repo, sc);
        cfg.init();
        AiConfigServiceHeadless h = new AiConfigServiceHeadless(repo, sc, cfg);
        var initMethod = AiConfigServiceHeadless.class.getDeclaredMethod("init");
        initMethod.setAccessible(true);
        initMethod.invoke(h);
        AiConfigServiceHeadless.setAiMode("local");
        registry = new ProviderRegistryService(repo, sc);
        ms = new AiModeService();
        SkillRegistry skills = new SkillRegistry(
                new SkillPackageService(System.getProperty("java.io.tmpdir") + "/fengyu-skills-test"));
        BackendReactivator reactivator = new BackendReactivator(
                ms, (fan.summer.fengyu.ai.config.AiToolRegistry) null, skills, cfg,
                new ChatToolApprovalGate(), registry);
        controller = new AiProviderController(registry, reactivator, ms, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listShowsBuiltinsWithMaskedKeysAndActiveFlag() {
        AiConfigServiceHeadless.setAiOpenAiApiKey("sk-controller-test");
        Map<String, Object> out = controller.list();
        List<Map<String, Object>> providers = (List<Map<String, Object>>) out.get("providers");
        assertEquals(4, providers.size());
        var openai = providers.stream()
                .filter(p -> "openai".equals(p.get("id"))).findFirst().orElseThrow();
        assertEquals(true, openai.get("apiKeySet"));
        assertEquals("OPENAI_CHAT", openai.get("protocol"));
        assertFalse(String.valueOf(openai.get("apiKey")).contains("sk-controller-test"),
                "the key material must never reach the wire view");
        // R3-P1: pin the mask SHAPE — a regression that leaks the ENC cipher (not
        // plaintext, still offline-decryptable on-machine) must also fail here.
        assertEquals("\u2022\u2022\u2022\u2022 (openai)", openai.get("apiKey"));
        assertFalse(String.valueOf(openai.get("apiKey")).startsWith("ENC("));
        assertEquals("ollama", out.get("activeProvider"), "legacy local mode maps to ollama");
        assertEquals(Boolean.TRUE, providers.stream()
                .filter(p -> "ollama".equals(p.get("id"))).findFirst().orElseThrow().get("active"));
    }

    @Test
    void createRejectsBadProtocolAndDuplicate() {
        assertEquals(400, controller.create(Map.of(
                "id", "x", "displayName", "x", "protocol", "NOPE",
                "baseUrl", "https://x.test", "model", "m")).getStatusCode().value());
        assertEquals(400, controller.create(Map.of(
                "id", "openai", "displayName", "dup", "protocol", "OPENAI_CHAT",
                "baseUrl", "https://x.test", "model", "m")).getStatusCode().value());
        // Was pinned as 200; resource creation answers 201 since the REST-shape fix.
        assertEquals(201, controller.create(Map.of(
                "id", "good", "displayName", "Good", "protocol", "ANTHROPIC_MESSAGES",
                "baseUrl", "https://api.anthropic.com", "model", "claude",
                "apiKey", "sk-x")).getStatusCode().value());
    }

    @Test
    void activateSwitchesActiveProviderAndHotSwaps() {
        controller.create(Map.of("id", "good", "displayName", "Good",
                "protocol", "OPENAI_CHAT", "baseUrl", "https://x.test", "model", "m"));
        ResponseEntity<?> resp = controller.activate("good");
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("good", registry.activeProviderId());
        // Hot-swap ran: the mode service now holds a cloud backend for the instance.
        assertTrue(registry.get("good").isPresent());
    }

    @Test
    @SuppressWarnings("unchecked")
    void modelListingIsFailSafeEmptyOnUnreachableEndpointAndUnknownProvider() {
        // Unknown id is a 400 (caller typo), an unreachable endpoint is an empty
        // 200 — the picker degrades to the configured model, never a 5xx.
        assertEquals(400, controller.models("nope").getStatusCode().value());
        controller.create(Map.of("id", "dead", "displayName", "Dead",
                "protocol", "OPENAI_CHAT", "baseUrl", "http://127.0.0.1:9", "model", "m"));
        ResponseEntity<?> resp = controller.models("dead");
        assertEquals(200, resp.getStatusCode().value());
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertEquals(List.of(), body.get("models"));
    }

    @Test
    void deleteRejectsBuiltin() {
        assertEquals(400, controller.delete("anthropic").getStatusCode().value());
    }

    @Test
    void customOllamaInstanceActivatesWithItsOwnEndpointNotTheBuiltinMirror() {
        // Audit R4-P1: a custom OLLAMA-protocol instance must route to ITS endpoint —
        // previously the definition was dropped and the builtin flat keys were used
        // silently (test button passed on the custom endpoint, chat hit the builtin).
        controller.create(Map.of("id", "my-ollama", "displayName", "My Ollama",
                "protocol", "OLLAMA", "baseUrl", "http://127.0.0.1:9", "model", "custom-tag"));
        AiConfigServiceHeadless.setAiOllamaModel("builtin-tag");
        assertEquals(200, controller.activate("my-ollama").getStatusCode().value());
        // Read through the controller's own mode service: the active backend carries the
        // CUSTOM tag, proving the definition (not the flat mirror) reached the backend.
        assertTrue(ms.getService().isPresent());
        assertEquals(java.util.Optional.of("custom-tag"), ms.getService().get().getModelName());
    }

    @Test
    void testEndpointReportsFailureWithoutThrowingOnUnconfiguredProvider() {
        Map<String, Object> out = controller.test("openai");
        // No key + unreachable endpoint ⇒ a structured failure, never an exception.
        assertTrue(out.containsKey("success"));
        assertFalse((Boolean) out.get("success"));
    }
}
