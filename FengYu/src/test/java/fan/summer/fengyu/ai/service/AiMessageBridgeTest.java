package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiMedia;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiMessageBridgeTest {

    @Test
    void imageToolHistoryKeepsToolProtocolThenAddsUserMediaPart() {
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47};
        AiChatMessage message = AiChatMessage.toolResult("call-1", "browser_screenshot",
                "{\"success\":true,\"imageAttached\":true}",
                List.of(new AiMedia("image/png", Base64.getEncoder().encodeToString(png), "shot.png")));

        var spring = AiMessageBridge.toSpringAiMessages(message);

        assertEquals(2, spring.size());
        assertInstanceOf(ToolResponseMessage.class, spring.getFirst());
        UserMessage visual = assertInstanceOf(UserMessage.class, spring.get(1));
        assertEquals(1, visual.getMedia().size());
        assertArrayEquals(png, visual.getMedia().getFirst().getDataAsByteArray());
    }
}
