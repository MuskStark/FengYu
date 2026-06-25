package fan.summer.ai.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Qwen3AdapterTest {

    @Test
    void augmentAddsHermesDirective() {
        String out = new Qwen3Adapter().augmentSystemPrompt("You are helpful.");
        assertTrue(out.contains("You are helpful."));
        assertTrue(out.contains("<tool_call>"));
    }

    @Test
    void thinkingEnabledByDefaultDoesNotEmitNoThink() {
        String out = new Qwen3Adapter().augmentSystemPrompt("base");
        assertFalse(out.contains("/no_think"));
    }

    @Test
    void disablingThinkingInjectsNoThink() {
        Qwen3Adapter a = new Qwen3Adapter();
        a.setThinkingEnabled(false);
        assertTrue(a.augmentSystemPrompt("base").contains("/no_think"));
    }

    @Test
    void nullBaseIsTolerated() {
        assertDoesNotThrow(() -> new Qwen3Adapter().augmentSystemPrompt(null));
    }
}
