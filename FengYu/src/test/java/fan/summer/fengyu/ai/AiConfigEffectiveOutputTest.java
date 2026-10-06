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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Pins the output-budget precedence on {@link AiConfigService}: an explicit
 * {@code ai.max_tokens} override wins, the legacy whole-form-persisted 2048 default is
 * treated as unset (falling through to the model catalog instead of the old flat 8192
 * upgrade), and unknown models keep the flat default. Same Mockito-facade pattern as
 * {@code AiConfigEffectiveWindowTest}.
 */
class AiConfigEffectiveOutputTest {

    private final Map<String, String> settings = new HashMap<>();

    @BeforeEach
    void setUp() {
        AppSettingRepository repo = Mockito.mock(AppSettingRepository.class);
        when(repo.findByUserIdAndSettingKey(anyLong(), any())).thenAnswer(invocation -> {
            String value = settings.get(invocation.getArgument(1));
            if (value == null) return Optional.empty();
            AppSettingEntity entity = new AppSettingEntity();
            entity.setSettingKey(invocation.getArgument(1));
            entity.setSettingValue(value);
            return Optional.of(entity);
        });
        new AiConfigService(repo, new NoopSecurityContext()).init();
    }

    @AfterEach
    void tearDown() {
        settings.clear();
        new AiConfigService(Mockito.mock(AppSettingRepository.class),
                new NoopSecurityContext()).init();
    }

    @Test
    void explicitOverrideWinsOverCatalog() {
        settings.put("ai.max_tokens", "1024");
        assertEquals(1024, AiConfigService.effectiveMaxOutputTokens("gpt-4o"));
    }

    @Test
    void legacy2048IsTreatedAsUnsetAndFallsToTheCatalog() {
        settings.put("ai.max_tokens", "2048");
        assertEquals(16_384, AiConfigService.effectiveMaxOutputTokens("gpt-4o"));
        assertEquals(131_072, AiConfigService.effectiveMaxOutputTokens("glm-4.7-flash"));
    }

    @Test
    void noSettingUsesTheCatalog() {
        assertEquals(16_384, AiConfigService.effectiveMaxOutputTokens("gpt-4o"));
        assertEquals(64_000, AiConfigService.effectiveMaxOutputTokens("claude-sonnet-4-20250514"));
    }

    @Test
    void unknownModelKeepsFlatDefault() {
        assertEquals(AiConfigService.DEFAULT_MAX_TOKENS,
                AiConfigService.effectiveMaxOutputTokens("mystery-model"));
        assertEquals(AiConfigService.DEFAULT_MAX_TOKENS,
                AiConfigService.effectiveMaxOutputTokens(null));
    }

    @Test
    void invalidStoredValueFallsThrough() {
        settings.put("ai.max_tokens", "not-a-number");
        assertEquals(16_384, AiConfigService.effectiveMaxOutputTokens("gpt-4o"));
    }

    @Test
    void nonPositiveStoredValueFallsThroughToTheCatalog() {
        // 0 / negative are not usable budgets — the catalog decides for the model.
        settings.put("ai.max_tokens", "0");
        assertEquals(16_384, AiConfigService.effectiveMaxOutputTokens("gpt-4o"));
        settings.put("ai.max_tokens", "-5");
        assertEquals(131_072, AiConfigService.effectiveMaxOutputTokens("glm-4.7-flash"));
    }
}
