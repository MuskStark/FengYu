package fan.summer.fengyu.api.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiChatMessageTest {

    @Test
    void nullRoleIsRejected() {
        assertThrows(NullPointerException.class,
            () -> new AiChatMessage(null, "hi", List.of(), null, null, null));
    }

    @Test
    void nullContentIsNormalizedToEmpty() {
        AiChatMessage m = new AiChatMessage(AiChatMessage.Role.ASSISTANT, null, List.of(), null, null, null);
        assertEquals("", m.content());
    }

    @Test
    void nullToolCallsIsNormalizedToEmptyList() {
        AiChatMessage m = new AiChatMessage(AiChatMessage.Role.USER, "q", null, null, null, null);
        assertNotNull(m.toolCalls());
        assertTrue(m.toolCalls().isEmpty());
    }
}
