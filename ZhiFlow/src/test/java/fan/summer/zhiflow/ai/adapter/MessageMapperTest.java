package fan.summer.zhiflow.ai.adapter;

import fan.summer.zhiflow.api.ai.AiChatMessage;
import fan.summer.zhiflow.api.ai.AiToolCall;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessageMapperTest {

    @Test
    void systemMessageRoundTrip() {
        Message m = MessageMapper.toSpringAi(AiChatMessage.system("You are helpful."));
        assertEquals(MessageType.SYSTEM, m.getMessageType());
        assertEquals("You are helpful.", m.getText());
    }

    @Test
    void userMessageRoundTrip() {
        Message m = MessageMapper.toSpringAi(AiChatMessage.user("Hi"));
        assertEquals(MessageType.USER, m.getMessageType());
        assertEquals("Hi", m.getText());
    }

    @Test
    void assistantWithToolCallsMapsToToolCallList() {
        AiToolCall call = AiToolCall.of("call_1", "get_weather", Map.of("city", "Zurich"));
        Message m = MessageMapper.toSpringAi(AiChatMessage.assistantWithTools("", List.of(call)));

        assertInstanceOf(AssistantMessage.class, m);
        AssistantMessage am = (AssistantMessage) m;
        assertTrue(am.hasToolCalls());
        assertEquals(1, am.getToolCalls().size());
        AssistantMessage.ToolCall tc = am.getToolCalls().get(0);
        assertEquals("call_1", tc.id());
        assertEquals("get_weather", tc.name());
    }

    @Test
    void toolResultMapsToToolResponseMessage() {
        AiChatMessage tr = AiChatMessage.toolResult("call_1", "get_weather", "sunny");
        Message m = MessageMapper.toSpringAi(tr);
        assertInstanceOf(ToolResponseMessage.class, m);
        ToolResponseMessage trm = (ToolResponseMessage) m;
        // Spring AI 2.0 GA: accessor is responseData(), not responseMessage().
        assertEquals("sunny", trm.getResponses().get(0).responseData());
        assertEquals("call_1", trm.getResponses().get(0).id());
    }

    @Test
    void extractToolCallsParsesArgumentsJson() {
        AssistantMessage am = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call_9", "function", "get_weather", "{\"city\":\"Zurich\"}")))
                .build();
        List<AiToolCall> calls = MessageMapper.extractToolCalls(am);
        assertEquals(1, calls.size());
        assertEquals("call_9", calls.get(0).id());
        assertEquals("get_weather", calls.get(0).name());
        assertEquals("Zurich", calls.get(0).arguments().get("city"));
    }
}
