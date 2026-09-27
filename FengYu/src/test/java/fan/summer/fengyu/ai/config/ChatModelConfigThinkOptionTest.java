package fan.summer.fengyu.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.ThinkOption;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatModelConfigThinkOptionTest {

    /** Unique per test: the capability cache is static and lives for the whole JVM. */
    private static final OllamaApi.ShowModelResponse THINKING_SHOW = show(List.of("completion", "thinking"));
    private static final OllamaApi.ShowModelResponse PLAIN_SHOW = show(List.of("completion"));

    private static OllamaApi.ShowModelResponse show(List<String> capabilities) {
        return new OllamaApi.ShowModelResponse(null, null, null, null, null, null, null, null, null,
                capabilities, null);
    }

    @Test
    void thinkingCapableModelGetsTheEnabledOption() {
        OllamaApi api = mock(OllamaApi.class);
        when(api.showModel(any())).thenReturn(THINKING_SHOW);

        ThinkOption option = ChatModelConfig.thinkingOption(api, "http://unique-a:11434", "qwen3:4b");

        assertEquals(ThinkOption.ThinkBoolean.ENABLED, option);
    }

    @Test
    void modelWithoutThinkingCapabilityKeepsTheOptionUnset() {
        OllamaApi api = mock(OllamaApi.class);
        when(api.showModel(any())).thenReturn(PLAIN_SHOW);

        assertNull(ChatModelConfig.thinkingOption(api, "http://unique-b:11434", "llama3.1:8b"));
    }

    @Test
    void capabilitiesWithoutTheThinkingEntryKeepTheOptionUnset() {
        OllamaApi api = mock(OllamaApi.class);
        when(api.showModel(any())).thenReturn(show(null));

        assertNull(ChatModelConfig.thinkingOption(api, "http://unique-c:11434", "llava:7b"));
    }

    @Test
    void unreachableServerLeavesTheOptionUnsetInsteadOfFailingTheBuild() {
        OllamaApi api = mock(OllamaApi.class);
        when(api.showModel(any())).thenThrow(new IllegalStateException("connection refused"));

        assertNull(ChatModelConfig.thinkingOption(api, "http://unique-d:11434", "qwen3:4b"));
    }

    @Test
    void gptOssTakesTheLevelFormOfTheThinkOption() {
        assertEquals(new ThinkOption.ThinkLevel("medium"), ChatModelConfig.thinkOptionFor("gpt-oss:20b"));
        assertEquals(ThinkOption.ThinkBoolean.ENABLED, ChatModelConfig.thinkOptionFor("qwen3:4b"));
        assertEquals(ThinkOption.ThinkBoolean.ENABLED, ChatModelConfig.thinkOptionFor(null));
    }
}
