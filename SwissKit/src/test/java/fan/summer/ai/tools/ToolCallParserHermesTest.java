package fan.summer.ai.tools;

import fan.summer.api.ai.AiToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallParserHermesTest {

    @Test
    void parsesSingleHermesToolCall() {
        String text = "<tool_call>\n{\"name\": \"get_weather\", \"arguments\": {\"city\": \"Beijing\"}}\n</tool_call>";
        List<AiToolCall> calls = ToolCallParser.parse(text);
        assertEquals(1, calls.size());
        assertEquals("get_weather", calls.get(0).name());
        assertEquals("Beijing", calls.get(0).arguments().get("city"));
    }

    @Test
    void parsesMultipleHermesToolCallsInOneTurn() {
        String text = "<tool_call>\n{\"name\": \"a\", \"arguments\": {\"x\": 1}}\n</tool_call>\n"
                    + "<tool_call>\n{\"name\": \"b\", \"arguments\": {\"y\": 2}}\n</tool_call>";
        List<AiToolCall> calls = ToolCallParser.parse(text);
        assertEquals(2, calls.size());
        assertEquals("a", calls.get(0).name());
        assertEquals("b", calls.get(1).name());
    }

    @Test
    void parsesHermesCallEmbeddedInProse() {
        String text = "I'll check that for you.\n<tool_call>\n{\"name\": \"hash\", \"arguments\": {\"algo\": \"md5\"}}\n</tool_call>\n";
        List<AiToolCall> calls = ToolCallParser.parse(text);
        assertEquals(1, calls.size());
        assertEquals("hash", calls.get(0).name());
    }

    @Test
    void stripToolCallsRemovesHermesBlock() {
        String text = "Prefix.\n<tool_call>\n{\"name\": \"a\", \"arguments\": {}}\n</tool_call>\nSuffix.";
        String stripped = ToolCallParser.stripToolCalls(text);
        assertTrue(stripped.contains("Prefix"));
        assertTrue(stripped.contains("Suffix"));
        assertFalse(stripped.contains("tool_call"));
    }

    @Test
    void hermesAndQwen25PatternsCoexist() {
        // Existing Qwen2.5 special-token form still parses (regression guard).
        String qwen25 = "<|tool_call_begin|>{\"name\":\"q\",\"arguments\":{\"k\":\"v\"}}<|tool_call_end|>";
        assertEquals("q", ToolCallParser.parse(qwen25).get(0).name());
    }
}
