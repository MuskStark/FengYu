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

        var result = ConversationCompactor.compact(history, 100, value -> {
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
