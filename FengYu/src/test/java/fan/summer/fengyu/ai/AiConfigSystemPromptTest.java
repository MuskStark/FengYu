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
 * Pins the system-prompt precedence on {@link AiConfigService}: a real override wins, while
 * a stored copy of a default — the legacy {@code "You are a helpful assistant."} or the current
 * text, both persisted whole-form by older Settings UIs — is treated as unset so the install
 * receives {@link SystemPrompts#DEFAULT_CHAT} (and its reply-language rule) instead of a frozen
 * past default. Same Mockito-facade pattern as {@code AiConfigEffectiveOutputTest}.
 */
class AiConfigSystemPromptTest {

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
    void noSettingUsesTheBuiltInDefault() {
        assertEquals("", AiConfigService.getAiSystemPromptStored());
        assertEquals(SystemPrompts.DEFAULT_CHAT, AiConfigService.getAiSystemPrompt());
    }

    @Test
    void blankStoredValueUsesTheBuiltInDefault() {
        settings.put("ai.system_prompt", "   \n");
        assertEquals("", AiConfigService.getAiSystemPromptStored());
        assertEquals(SystemPrompts.DEFAULT_CHAT, AiConfigService.getAiSystemPrompt());
    }

    @Test
    void frozenLegacyDefaultIsTreatedAsUnset() {
        settings.put("ai.system_prompt", AiConfigService.LEGACY_DEFAULT_SYSTEM_PROMPT);
        assertEquals("", AiConfigService.getAiSystemPromptStored());
        assertEquals(SystemPrompts.DEFAULT_CHAT, AiConfigService.getAiSystemPrompt());
    }

    @Test
    void frozenLegacyDefaultWithSurroundingWhitespaceIsTreatedAsUnset() {
        settings.put("ai.system_prompt", "  " + AiConfigService.LEGACY_DEFAULT_SYSTEM_PROMPT + "\n");
        assertEquals(SystemPrompts.DEFAULT_CHAT, AiConfigService.getAiSystemPrompt());
    }

    @Test
    void frozenCopyOfTheCurrentDefaultIsTreatedAsUnset() {
        settings.put("ai.system_prompt", SystemPrompts.DEFAULT_CHAT + "\n");
        assertEquals("", AiConfigService.getAiSystemPromptStored());
        assertEquals(SystemPrompts.DEFAULT_CHAT, AiConfigService.getAiSystemPrompt());
    }

    @Test
    void customPromptWinsAndIsReportedAsStored() {
        String custom = "You are a terse SQL tutor. Answer in French.";
        settings.put("ai.system_prompt", custom);
        assertEquals(custom, AiConfigService.getAiSystemPromptStored());
        assertEquals(custom, AiConfigService.getAiSystemPrompt());
    }

    @Test
    void editedDefaultIsAUserChoiceAndIsKept() {
        // One changed character means the user deliberately pinned their own variant.
        String edited = SystemPrompts.DEFAULT_CHAT + "\n- Always sign off with a haiku.";
        settings.put("ai.system_prompt", edited);
        assertEquals(edited, AiConfigService.getAiSystemPrompt());
    }
}
