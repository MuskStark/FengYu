package fan.summer.fengyu.ai.session;

import fan.summer.fengyu.ai.AiChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Microcompact (4.1.0): over the trigger threshold, the CONTENTS of old tool results are
 * evicted (placeholder, pairing preserved) before any summarization is attempted; under
 * the threshold nothing changes.
 */
class ConversationCompactorMicrocompactTest {

    private static AiChatMessage bigToolResult(String id, int chars) {
        return AiChatMessage.toolResult(id, "read_file", "x".repeat(chars));
    }

    @Test
    void evictsOldToolResultContentsKeepsRecentRounds() {
        List<AiChatMessage> history = new ArrayList<>();
        history.add(AiChatMessage.user("u1"));
        history.add(bigToolResult("t1", 2000));
        history.add(AiChatMessage.user("u2"));
        history.add(bigToolResult("t2", 2000));
        // Recent working set: the last MICROCOMPACT_KEEP_ROUNDS user rounds stay verbatim.
        history.add(AiChatMessage.user("u3"));
        history.add(bigToolResult("t3", 2000));
        history.add(AiChatMessage.user("u4"));
        history.add(bigToolResult("t4", 2000));
        history.add(AiChatMessage.user("u5"));
        history.add(bigToolResult("t5", 2000));

        List<AiChatMessage> compacted = ConversationCompactor.microcompact(List.copyOf(history));

        assertNotSame(history, compacted, "bulky old tool results must be evicted");
        assertEquals(history.size(), compacted.size(), "eviction never drops messages");
        for (int i = 0; i < compacted.size(); i++) {
            assertEquals(history.get(i).role(), compacted.get(i).role(), "roles keep order");
        }
        assertEquals(ConversationCompactor.EVICTED_PLACEHOLDER, compacted.get(1).content(),
                "the oldest tool result content is the placeholder");
        assertEquals("x".repeat(2000), compacted.get(compacted.size() - 1).content(),
                "the newest tool result stays verbatim");
        assertEquals("read_file", compacted.get(1).toolName(), "tool identity survives eviction");
        assertEquals("t1", compacted.get(1).toolCallId(), "tool call id survives eviction");
    }

    @Test
    void underKeepRoundsEverythingStaysVerbatimWhenBulky() {
        // Fewer than MICROCOMPACT_KEEP_ROUNDS rounds: everything is "the recent working set"
        // except tool results before the 4th-last user round — with 3 rounds only, all tool
        // results are recent, so nothing is evicted.
        List<AiChatMessage> history = List.of(
                AiChatMessage.user("u1"), bigToolResult("t1", 2000),
                AiChatMessage.user("u2"), bigToolResult("t2", 2000),
                AiChatMessage.user("u3"), bigToolResult("t3", 2000));
        List<AiChatMessage> compacted = ConversationCompactor.microcompact(history);
        assertEquals("x".repeat(2000), compacted.get(1).content());
    }

    @Test
    void smallOrAbsentToolResultsReturnSameList() {
        List<AiChatMessage> history = List.of(
                AiChatMessage.user("u1"),
                AiChatMessage.toolResult("t1", "read_file", "small"),
                AiChatMessage.assistant("done"));
        assertSame(history, ConversationCompactor.microcompact(history),
                "nothing to evict → the same reference (no allocation)");
    }

    @Test
    void compactUsesMicrocompactPhaseBeforeSummarizing() {
        // Bulky OLD tool results + small recent working set: microcompact alone brings the
        // estimate under the trigger, so the summarizer never runs (it throws here — an
        // invocation would fail the test).
        List<AiChatMessage> history = new ArrayList<>();
        history.add(AiChatMessage.user("u1"));
        history.add(bigToolResult("t1", 12_000));
        history.add(AiChatMessage.user("u2"));
        history.add(bigToolResult("t2", 12_000));
        history.add(AiChatMessage.user("u3"));
        history.add(AiChatMessage.toolResult("t3", "read_file", "small recent"));
        history.add(AiChatMessage.user("u4"));
        history.add(AiChatMessage.toolResult("t4", "read_file", "small recent"));
        history.add(AiChatMessage.user("final"));
        history.add(AiChatMessage.assistant("answer"));

        ConversationCompactor.Result result = ConversationCompactor.compact(
                history, 8_000, 0,
                transcript -> { throw new AssertionError("summarizer must not run for microcompact-only fits"); });

        assertTrue(result.compacted());
        assertTrue(result.microcompacted());
        assertTrue(result.estimatedTokensAfter() < result.estimatedTokensBefore());
        assertFalse(result.history().stream()
                .anyMatch(m -> m.content().startsWith(ConversationCompactor.SUMMARY_PREFIX)),
                "microcompact-only fits never inject a summary message");
    }
}
