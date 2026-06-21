package fan.summer.ai.adapter;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import fan.summer.api.ai.AiChatMessage;
import fan.summer.api.ai.AiToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChatMessageMapperTest {

    @Test
    void mapsSystemMessage() {
        AiChatMessage src = AiChatMessage.system("You are helpful");
        SystemMessage out = (SystemMessage) ChatMessageMapper.toLc4j(src);
        assertEquals("You are helpful", out.text());
    }

    @Test
    void mapsUserMessage() {
        AiChatMessage src = AiChatMessage.user("Hi");
        UserMessage out = (UserMessage) ChatMessageMapper.toLc4j(src);
        assertEquals("Hi", out.singleText());
    }

    @Test
    void mapsAssistantMessagePlain() {
        AiChatMessage src = AiChatMessage.assistant("Hello!");
        AiMessage out = (AiMessage) ChatMessageMapper.toLc4j(src);
        assertEquals("Hello!", out.text());
        assertTrue(out.toolExecutionRequests() == null || out.toolExecutionRequests().isEmpty());
    }

    @Test
    void mapsAssistantMessageWithToolCalls() {
        // NOTE: AiToolCall.of(name, Map) — arguments is a Map<String,Object>, not a JSON string.
        // The mapper serializes the Map to JSON when building the LangChain4j request.
        AiToolCall call = AiToolCall.of("get_weather", Map.of("city", "Paris"));
        AiChatMessage src = AiChatMessage.assistantWithTools("", List.of(call));

        AiMessage out = (AiMessage) ChatMessageMapper.toLc4j(src);

        assertEquals(1, out.toolExecutionRequests().size());
        ToolExecutionRequest req = out.toolExecutionRequests().get(0);
        assertEquals("get_weather", req.name());
        assertEquals("{\"city\":\"Paris\"}", req.arguments());
        assertNotNull(req.id());
    }

    @Test
    void mapsToolResultMessage() {
        AiChatMessage src = AiChatMessage.toolResult("call_1", "get_weather", "Sunny, 22°C");

        ToolExecutionResultMessage out = (ToolExecutionResultMessage) ChatMessageMapper.toLc4j(src);

        assertEquals("call_1", out.id());
        assertEquals("Sunny, 22°C", out.text());
    }

    @Test
    void roundTripPreservesRoles() {
        AiChatMessage user = AiChatMessage.user("hi");
        ChatMessage lc = ChatMessageMapper.toLc4j(user);
        AiChatMessage back = ChatMessageMapper.fromLc4j(lc);
        assertEquals(AiChatMessage.Role.USER, back.role());
        assertEquals("hi", back.content());
    }

    @Test
    void fromLc4jMapsAssistantWithToolCalls() {
        AiMessage src = AiMessage.from("", List.of(
            ToolExecutionRequest.builder()
                .id("call_42").name("get_weather").arguments("{\"city\":\"Paris\"}")
                .build()
        ));
        AiChatMessage out = ChatMessageMapper.fromLc4j(src);

        assertEquals(AiChatMessage.Role.ASSISTANT, out.role());
        assertEquals(1, out.toolCalls().size());
        AiToolCall call = out.toolCalls().get(0);
        assertEquals("get_weather", call.name());
        assertEquals(Map.of("city", "Paris"), call.arguments());
    }

    @Test
    void fromLc4jThrowsOnUnsupportedType() {
        // CustomMessage is a ChatMessage subclass not handled by the mapper.
        // Its constructor takes a Map<String,Object>, not a String.
        dev.langchain4j.data.message.ChatMessage custom =
            new dev.langchain4j.data.message.CustomMessage(Map.of("text", "text-only"));
        assertThrows(IllegalArgumentException.class,
            () -> ChatMessageMapper.fromLc4j(custom));
    }
}
