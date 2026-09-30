package fan.summer.fengyu.ai.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolResultContextLimiterTest {

    @Test
    void preservesSmallToolResponses() {
        List<Message> source = List.of(toolResponse("small"));
        ToolResponseMessage limited = (ToolResponseMessage) ToolResultContextLimiter.limit(source).getFirst();
        assertEquals("small", limited.getResponses().getFirst().responseData());
    }

    @Test
    void keepsHeadAndTailWhileFoldingLargeMiddle() {
        String source = "H".repeat(ToolResultContextLimiter.MAX_RESPONSE_CHARS)
                + "MIDDLE" + "T".repeat(10_000);
        String limited = ToolResultContextLimiter.limit(source);
        assertTrue(limited.startsWith("H".repeat(100)));
        assertTrue(limited.endsWith("T".repeat(100)));
        assertTrue(limited.contains("FengYu omitted"));
        assertTrue(limited.length() <= ToolResultContextLimiter.MAX_RESPONSE_CHARS);
        assertTrue(limited.length() < source.length());
    }

    @Test
    void leavesNonToolMessagesInPlace() {
        AssistantMessage assistant = new AssistantMessage("answer");
        List<Message> limited = ToolResultContextLimiter.limit(
                List.of(assistant, toolResponse("ok")));
        assertSame(assistant, limited.getFirst());
        assertInstanceOf(ToolResponseMessage.class, limited.getLast());
    }

    private static ToolResponseMessage toolResponse(String value) {
        return ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("id", "tool", value))).build();
    }
}
