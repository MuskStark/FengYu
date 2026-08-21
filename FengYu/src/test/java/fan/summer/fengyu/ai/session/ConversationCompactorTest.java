package fan.summer.fengyu.ai.session;

import fan.summer.fengyu.ai.AiChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ConversationCompactorTest {

    @Test
    void keepsShortHistoryVerbatim() {
        List<AiChatMessage> history = List.of(
                AiChatMessage.system("system"), AiChatMessage.user("hello"));

        var result = ConversationCompactor.compact(history, 32_768,
                ignored -> fail("short history must not call the summarizer"));

        assertFalse(result.compacted());
        assertEquals(history, result.history());
    }

    @Test
    void summarizesOldRoundsAndKeepsRecentRoundsVerbatim() {
        List<AiChatMessage> history = new ArrayList<>();
        history.add(AiChatMessage.system("stable instructions"));
        for (int round = 1; round <= 10; round++) {
            history.add(AiChatMessage.user("user-" + round + " ".repeat(80)));
            history.add(AiChatMessage.assistant("assistant-" + round + " ".repeat(80)));
        }
        AtomicReference<String> transcript = new AtomicReference<>();

        var result = ConversationCompactor.compact(history, 800, value -> {
            transcript.set(value);
            return "goals and decisions";
        });

        assertTrue(result.compacted());
        assertTrue(transcript.get().contains("user-1"));
        assertTrue(transcript.get().contains("assistant-2"));
        assertFalse(transcript.get().contains("user-3"));
        assertEquals(AiChatMessage.Role.SYSTEM, result.history().get(0).role());
        assertTrue(result.history().get(1).content().startsWith(
                ConversationCompactor.SUMMARY_PREFIX));
        assertTrue(result.history().get(2).content().startsWith("user-3"));
        assertEquals("assistant-10" + " ".repeat(80), result.history().getLast().content());
        assertTrue(result.estimatedTokensAfter() < result.estimatedTokensBefore());
    }

    @Test
    void shrinksRecentRoundsWhenEvenTheTailOverflowsTheWindow() {
        // window=100 with ~60-token rounds: the default 8-round tail cannot fit, so rounds are
        // traded away down to MIN_RECENT_ROUNDS instead of returning an oversized history.
        List<AiChatMessage> history = longHistory();
        var result = ConversationCompactor.compact(history, 100, ignored -> "summary");

        assertTrue(result.compacted());
        // Kept tail starts at a user-turn boundary and holds only the minimum recent rounds.
        assertEquals(AiChatMessage.Role.USER, result.history().get(1).role());
        assertTrue(result.history().get(1).content().startsWith("u9"));
        assertEquals("a10" + "x".repeat(100), result.history().getLast().content());
        assertTrue(result.estimatedTokensAfter() < result.estimatedTokensBefore());
    }

    @Test
    void truncatesToolResultsInTheSummarizerTranscript() {
        List<AiChatMessage> history = new ArrayList<>();
        history.add(AiChatMessage.user("list files"));
        history.add(AiChatMessage.assistantWithTools("",
                List.of(fan.summer.fengyu.ai.AiToolCall.of("tc-1", "execute_command",
                        java.util.Map.of("cmd", "ls")))));
        history.add(AiChatMessage.toolResult("tc-1", "execute_command", "y".repeat(5_000)));
        for (int round = 0; round < 8; round++) {
            history.add(AiChatMessage.user("u" + round + " " + "z".repeat(80)));
            history.add(AiChatMessage.assistant("a" + round + " " + "z".repeat(80)));
        }
        AtomicReference<String> transcript = new AtomicReference<>();

        var result = ConversationCompactor.compact(history, 2_000, value -> {
            transcript.set(value);
            return "summary";
        });

        assertTrue(result.compacted());
        String rendered = transcript.get();
        assertTrue(rendered.contains("[truncated]"));
        assertTrue(rendered.length() < 5_000);
        // The tool call/result pair stays together on the summarized side of the cut.
        assertTrue(rendered.contains("TOOL(execute_command)"));
    }

    @Test
    void retriesSummarizationOnceWithAShorterSliceBeforeFailingOpen() {
        List<AiChatMessage> history = longHistory();
        List<Integer> sizes = new ArrayList<>();
        var result = ConversationCompactor.compact(history, 800, transcript -> {
            sizes.add(transcript.length());
            if (sizes.size() == 1) throw new IllegalStateException("provider unavailable");
            return "second-attempt summary";
        });

        assertTrue(result.compacted());
        assertEquals(2, sizes.size());
        assertTrue(sizes.get(1) < sizes.get(0));
    }

    @Test
    void summarizerFailureLeavesConversationUntouched() {
        List<AiChatMessage> history = longHistory();
        var result = ConversationCompactor.compact(history, 100,
                ignored -> { throw new IllegalStateException("provider unavailable"); });
        assertFalse(result.compacted());
        assertEquals(history, result.history());
    }

    @Test
    void utf8EstimateDoesNotSeverelyUndercountChineseText() {
        int estimate = ConversationCompactor.estimateTokens(
                List.of(AiChatMessage.user("蜂语上下文压缩".repeat(100))));
        assertTrue(estimate >= 500);
    }

    private static List<AiChatMessage> longHistory() {
        List<AiChatMessage> history = new ArrayList<>();
        for (int round = 1; round <= 10; round++) {
            history.add(AiChatMessage.user("u" + round + "x".repeat(100)));
            history.add(AiChatMessage.assistant("a" + round + "x".repeat(100)));
        }
        return history;
    }
}
