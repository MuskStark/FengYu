package fan.summer.fengyu.ai.provider;

import fan.summer.fengyu.ai.AiConfigService;
import fan.summer.fengyu.ai.service.AiConfigServiceHeadless;
import fan.summer.fengyu.FengYuApplication;
import fan.summer.fengyu.database.repository.AppSettingRepository;
import fan.summer.fengyu.security.NoopSecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch B1 registry semantics: legacy-derived read view (no startup mutation),
 * first-write migration (snapshot + legacy key blanking), builtin mirroring,
 * activation + legacy mode mirror, and CRUD validation.
 */
@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = FengYuApplication.class)
class ProviderRegistryServiceTest {

    @Autowired private AppSettingRepository repo;

    private ProviderRegistryService registry;

    @BeforeEach
    void setUp() throws Exception {
        var sc = new NoopSecurityContext();
        AiConfigService cfg = new AiConfigService(repo, sc);
        cfg.init();
        AiConfigServiceHeadless h = new AiConfigServiceHeadless(repo, sc, cfg);
        var initMethod = AiConfigServiceHeadless.class.getDeclaredMethod("init");
        initMethod.setAccessible(true);
        initMethod.invoke(h);
        // Deterministic legacy state for the derivation view.
        AiConfigServiceHeadless.setAiMode("openai");
        AiConfigServiceHeadless.setAiOpenAiEndpoint("https://api.openai.com");
        AiConfigServiceHeadless.setAiOpenAiApiKey("sk-openai-test");
        AiConfigServiceHeadless.setAiOpenAiModel("gpt-4o");
        AiConfigServiceHeadless.setAiAnthropicApiKey("sk-ant-test");
        AiConfigServiceHeadless.setAiDeepSeekApiKey("");
        registry = new ProviderRegistryService(repo, sc);
    }

    private String raw(String key) {
        return repo.findByUserIdAndSettingKey(1L, key).map(e -> e.getSettingValue()).orElse(null);
    }

    @Test
    void readDerivesBuiltinsFromLegacyWithoutPersisting() {
        var list = registry.list();
        assertEquals(4, list.size());
        var openai = registry.get("openai").orElseThrow();
        assertEquals(Protocol.OPENAI_CHAT, openai.protocol());
        assertEquals("gpt-4o", openai.model());
        assertTrue(openai.hasApiKey());
        assertEquals("openai", registry.activeProviderId(), "legacy mode openai maps to builtin openai");
        // The read path never mutates: nothing persisted, legacy keys untouched.
        assertEquals(null, raw(ProviderRegistryService.REGISTRY_KEY));
        assertEquals("sk-openai-test", AiConfigService.getAiOpenAiApiKey());
    }

    @Test
    void resolveApiKeyRoundTripsThroughTheEnvelope() {
        assertEquals("sk-openai-test", registry.resolveApiKey("openai"));
        assertEquals("sk-ant-test", registry.resolveApiKey("anthropic"));
        assertEquals("", registry.resolveApiKey("deepseek"), "unset key resolves empty");
        // The stored cipher is never the plaintext.
        var openai = registry.get("openai").orElseThrow();
        assertNotEquals("sk-openai-test", openai.apiKeyCipher());
        assertFalse(openai.apiKeyCipher().contains("sk-openai-test"));
    }

    @Test
    void firstWriteMigratesSnapshotAndBlanksLegacyKeys() {
        registry.create("my-zhipu", "Zhipu", Protocol.OPENAI_CHAT,
                "https://open.bigmodel.cn/api/paas/v4", "glm-4.7", "zp-key", null);

        // Registry persisted with 5 instances (4 migrated + 1 custom).
        assertTrue(raw(ProviderRegistryService.REGISTRY_KEY).contains("my-zhipu"));
        assertEquals(5, registry.list().size());
        // Legacy API keys blanked after the snapshot landed; derived values survive.
        assertEquals("", raw("ai.openai.api_key"));
        assertEquals("", raw("ai.anthropic.api_key"));
        assertEquals("sk-openai-test", registry.resolveApiKey("openai"),
                "the migrated snapshot still carries the pre-blank key");
    }

    @Test
    void createValidatesIdSlugProtocolAndUrl() {
        assertThrows(IllegalArgumentException.class, () ->
                registry.create("Bad_Id!", "x", Protocol.OPENAI_CHAT, "https://a.test", "m", null, null));
        assertThrows(IllegalArgumentException.class, () ->
                registry.create("openai", "dup", Protocol.OPENAI_CHAT, "https://a.test", "m", null, null));
        assertThrows(IllegalArgumentException.class, () ->
                registry.create("ok-id", "x", Protocol.OPENAI_CHAT, "ftp://nope", "m", null, null));
    }

    @Test
    void updateBuiltinMirrorsLegacyFlatKeys() {
        registry.update("openai", null, "https://gw.internal/v1", "gpt-4o-mini", "sk-new", null);
        assertEquals("https://gw.internal/v1", AiConfigService.getAiOpenAiEndpoint());
        assertEquals("gpt-4o-mini", AiConfigService.getAiOpenAiModel());
        assertEquals("sk-new", AiConfigService.getAiOpenAiApiKey());
        assertEquals("sk-new", registry.resolveApiKey("openai"));
    }

    @Test
    void updateBlankKeyKeepsStoredCredential() {
        registry.update("openai", null, null, null, "", null);
        assertEquals("sk-openai-test", registry.resolveApiKey("openai"));
    }

    @Test
    void maskedPlaceholderRoundTripNeverReplacesTheCredential() {
        // GET hands back \u2022\u2022\u2022\u2022 (id); a client that echoes it into PUT
        // must not silently destroy the stored key (audit P1-2).
        registry.update("openai", null, null, null, "\u2022\u2022\u2022\u2022 (openai)", null);
        assertEquals("sk-openai-test", registry.resolveApiKey("openai"));
        registry.update("openai", null, null, null, "sk-***-masked", null);
        assertEquals("sk-openai-test", registry.resolveApiKey("openai"));
    }

    @Test
    void activateWritesActiveAndMirrorsLegacyMode() {
        registry.activate("anthropic");
        assertEquals("anthropic", registry.activeProviderId());
        assertEquals("anthropic", AiConfigService.getAiMode());
        registry.create("my-kimi", "Kimi", Protocol.OPENAI_CHAT,
                "https://api.moonshot.cn/v1", "kimi-k2", "k-key", null);
        registry.activate("my-kimi");
        assertEquals("my-kimi", registry.activeProviderId());
        assertEquals("openai", AiConfigService.getAiMode(),
                "a custom OpenAI-compatible instance mirrors the closest legacy mode");
    }

    @Test
    void deleteRejectsBuiltinsAndRemovesCustom() {
        assertThrows(IllegalArgumentException.class, () -> registry.delete("openai"));
        registry.create("temp", "Temp", Protocol.OPENAI_CHAT, "https://t.test", "m", null, null);
        registry.delete("temp");
        assertTrue(registry.get("temp").isEmpty());
    }

    @Test
    void unreadableRegistryJsonFallsBackToLegacyView() {
        registry.create("first", "First", Protocol.OPENAI_CHAT, "https://a.test", "m", null, null);
        // Corrupt the persisted JSON; reads must degrade to the legacy-derived view.
        var entity = repo.findByUserIdAndSettingKey(1L, ProviderRegistryService.REGISTRY_KEY).orElseThrow();
        entity.setSettingValue("{not json");
        repo.save(entity);
        assertEquals(4, registry.list().size(), "corrupt JSON degrades to the legacy view, never crashes");
    }

    @Test
    void headersRideAlongAndRoundTrip() {
        registry.create("hdr", "Headers", Protocol.OPENAI_CHAT, "https://h.test", "m", null,
                Map.of("X-Org", "fengyu"));
        var stored = registry.get("hdr").orElseThrow();
        assertEquals(Map.of("X-Org", "fengyu"), stored.headers());
    }
}
