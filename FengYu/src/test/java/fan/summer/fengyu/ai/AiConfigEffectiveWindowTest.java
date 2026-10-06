package fan.summer.fengyu.ai;

import fan.summer.fengyu.database.entity.AppSettingEntity;
import fan.summer.fengyu.database.repository.AppSettingRepository;
import fan.summer.fengyu.security.NoopSecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Pins the per-model context-window precedence and the image-capability gate on
 * {@link AiConfigService}: an explicit {@code ai.context_window_tokens} setting always
 * wins (including {@code 0} = compaction off), the bundled catalog supplies the real
 * window for known models when the setting is absent, and the flat default remains the
 * unknown-model fallback. Uses a Mockito-backed repository so no JPA context is needed.
 */
class AiConfigEffectiveWindowTest {

    private AppSettingRepository repo;
    private final Map<String, String> settings = new HashMap<>();

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(AppSettingRepository.class);
        when(repo.findByUserIdAndSettingKey(anyLong(), any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(1);
            String value = settings.get(key);
            if (value == null) return Optional.empty();
            AppSettingEntity entity = new AppSettingEntity();
            entity.setSettingKey(key);
            entity.setSettingValue(value);
            return Optional.of(entity);
        });
        new AiConfigService(repo, new NoopSecurityContext()).init();
    }

    @AfterEach
    void tearDown() {
        settings.clear();
        // The static INSTANCE is shared across test classes; leave a clean no-setting
        // state so a later test class re-seeds its own facade.
        new AiConfigService(Mockito.mock(AppSettingRepository.class), new NoopSecurityContext()).init();
    }

    private void mode(String value) { settings.put("ai.mode", value); }

    @Test
    void explicitSettingWinsOverCatalogAndDefault() {
        settings.put("ai.context_window_tokens", "65536");
        assertEquals(65_536, AiConfigService.effectiveContextWindowTokens("gpt-4o"));
        assertEquals(65_536, AiConfigService.effectiveContextWindowTokens("never-heard-of-it"));
        // 0 is the explicit "compaction off" contract, not an unparseable value.
        settings.put("ai.context_window_tokens", "0");
        assertEquals(0, AiConfigService.effectiveContextWindowTokens("gpt-4o"));
    }

    @Test
    void catalogSuppliesWindowForKnownModelsWithoutASetting() {
        assertEquals(128_000, AiConfigService.effectiveContextWindowTokens("gpt-4o"));
        assertEquals(200_000, AiConfigService.effectiveContextWindowTokens("claude-sonnet-4-20250514"));
        assertEquals(128_000, AiConfigService.effectiveContextWindowTokens("deepseek-chat"));
    }

    @Test
    void unknownModelFallsBackToLegacyDefault() {
        assertEquals(AiConfigService.DEFAULT_CONTEXT_WINDOW_TOKENS,
                AiConfigService.effectiveContextWindowTokens("mystery-model"));
    }

    @Test
    void unparseableSettingFallsThroughToCatalog() {
        settings.put("ai.context_window_tokens", "not-a-number");
        assertEquals(128_000, AiConfigService.effectiveContextWindowTokens("gpt-4o"));
    }

    @Test
    void imageSupportIsGatedByActiveModeAndCatalog() {
        mode("deepseek");
        assertFalse(AiConfigService.activeModelSupportsImages());
        settings.put("ai.deepseek.model", "deepseek-chat");

        mode("openai");
        settings.put("ai.openai.model", "gpt-4o");
        assertTrue(AiConfigService.activeModelSupportsImages());

        // Unknown model ids stay permissive — the media fallback recovers a wrong guess.
        settings.put("ai.openai.model", "my-private-finetune");
        assertTrue(AiConfigService.activeModelSupportsImages());

        mode("local");
        settings.put("ai.ollama.model", "qwen3:4b");
        assertFalse(AiConfigService.activeModelSupportsImages());
    }
}
