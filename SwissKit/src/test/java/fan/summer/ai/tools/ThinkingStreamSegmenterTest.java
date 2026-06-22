package fan.summer.ai.tools;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ThinkingStreamSegmenterTest {

    private record Collected(List<String> think, List<String> content) {}

    /** Feeds tokens one per call, collecting every segment emitted across all feeds. */
    private static Collected run(String... tokens) {
        var seg = new ThinkingStreamSegmenter();
        List<String> think = new ArrayList<>(), content = new ArrayList<>();
        for (String t : tokens) {
            for (var s : seg.feed(t)) {
                if (s.type() == ThinkingStreamSegmenter.Type.THINK) think.add(s.text());
                else content.add(s.text());
            }
        }
        return new Collected(think, content);
    }

    /** Feeds tokens, then {@code flush()}, collecting all segments. */
    private static Collected runWithFlush(String... tokens) {
        var seg = new ThinkingStreamSegmenter();
        List<String> think = new ArrayList<>(), content = new ArrayList<>();
        for (String t : tokens) {
            for (var s : seg.feed(t)) {
                if (s.type() == ThinkingStreamSegmenter.Type.THINK) think.add(s.text());
                else content.add(s.text());
            }
        }
        for (var s : seg.flush()) {
            if (s.type() == ThinkingStreamSegmenter.Type.THINK) think.add(s.text());
            else content.add(s.text());
        }
        return new Collected(think, content);
    }

    @Test
    void pureContentPassesThrough() {
        var c = run("Hello world");
        assertEquals(List.of("Hello world"), c.content());
        assertTrue(c.think().isEmpty());
    }

    @Test
    void thinkBlockRoutedToThinkAndContent() {
        var c = run("Before ", "<think>", "reasoning here", "</think>", "After text");
        assertEquals(List.of("reasoning here"), c.think());
        assertEquals(List.of("Before ", "After text"), c.content());
    }

    @Test
    void toolCallRegionIsSuppressed() {
        var c = run("OK <tool_call>{\"name\":\"x\",\"arguments\":{}}</tool_call> done");
        assertEquals(List.of("OK ", " done"), c.content());
        assertTrue(c.think().isEmpty());
    }

    @Test
    void splitThinkMarkerAcrossTokensHoldsBack() {
        var c = run("Hej <thi", "nk>secret", "</think>", "end");
        assertEquals(List.of("Hej ", "end"), c.content());
        assertEquals(List.of("secret"), c.think());
    }

    @Test
    void splitToolCallOpenMarkerHoldsBack() {
        var c = run("pre <tool_", "call>{\"name\":\"y\",\"arguments\":{}}", "</tool_call>post");
        assertEquals(List.of("pre ", "post"), c.content());
        assertTrue(c.think().isEmpty());
    }

    @Test
    void flushEmitsTrailingContent() {
        var c = runWithFlush("just text");
        assertEquals(List.of("just text"), c.content());
    }

    @Test
    void flushEmitsUnclosedThinkAsThink() {
        var c = runWithFlush("<think>partial with no close");
        assertEquals(List.of("partial with no close"), c.think());
    }

    @Test
    void flushDiscardsUnclosedToolCall() {
        var c = runWithFlush("visible <tool_call>{\"name\":\"z\",\"arguments\":");
        assertEquals(List.of("visible "), c.content());
        assertTrue(c.think().isEmpty());
    }

    @Test
    void multipleThinkBlocks() {
        var c = run("<think>t1</think>A<think>t2</think>B");
        assertEquals(List.of("t1", "t2"), c.think());
        assertEquals(List.of("A", "B"), c.content());
    }

    @Test
    void stripThinkRemovesClosedAndUnclosedBlocks() {
        // Realistic shapes: a leading closed block, or a trailing unclosed one.
        assertEquals("The answer", ThinkingStreamSegmenter.stripThink("<think>reasoning</think>The answer"));
        assertEquals("a b", ThinkingStreamSegmenter.stripThink("a <think>x</think>b"));
        assertEquals("Hello", ThinkingStreamSegmenter.stripThink("Hello<think>partial"));
        assertEquals("", ThinkingStreamSegmenter.stripThink(null));
    }
}
