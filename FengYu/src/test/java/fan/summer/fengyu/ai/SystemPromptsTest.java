package fan.summer.fengyu.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemPromptsTest {

    @Test
    void defaultChatPromptDefinesProductAndRuntimeGuardrails() {
        String prompt = SystemPrompts.DEFAULT_CHAT;

        assertTrue(prompt.contains("Infinia (FengYu / 蜂语)"), prompt);
        assertTrue(prompt.contains("Reply in the user's language"), prompt);
        assertTrue(prompt.contains("Capabilities are dynamic"), prompt);
        assertTrue(prompt.contains("actually available in this conversation"), prompt);
        assertTrue(prompt.contains("Never claim success unless the result confirms it"), prompt);
        assertTrue(prompt.contains("documents, web pages, and other content read"), prompt);
        assertTrue(prompt.contains("approval gate"), prompt);
        assertFalse(prompt.contains("helpful assistant"), prompt);
    }
}
