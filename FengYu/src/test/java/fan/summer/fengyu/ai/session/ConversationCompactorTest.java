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
        // 4.1.0: bulky old TOOL results are evicted by microcompact FIRST (lossless), so this
        // fixture must drive the bulk through USER/ASSISTANT text to reach the summarizer —
        // which then still truncates tool results inside its transcript input.
        List<AiChatMessage> history = new ArrayList<>();
        history.add(AiChatMessage.user("list files"));
        history.add(AiChatMessage.assistantWithTools("",
                List.of(fan.summer.fengyu.ai.AiToolCall.of("tc-1", "execute_command",
                        java.util.Map.of("cmd", "ls")))));
        history.add(AiChatMessage.toolResult("tc-1", "execute_command", "y".repeat(5_000)));
        for (int round = 0; round < 8; round++) {
            history.add(AiChatMessage.user("u" + round + " " + "z".repeat(600)));
            history.add(AiChatMessage.assistant("a" + round + " " + "z".repeat(600)));
        }
        AtomicReference<String> transcript = new AtomicReference<>();

        var result = ConversationCompactor.compact(history, 2_000, value -> {
            transcript.set(value);
            return "summary";
        });

        assertTrue(result.compacted());
        String rendered = transcript.get();
        assertTrue(rendered.contains("TOOL(execute_command)"));
        assertTrue(rendered.length() < 5_000);
        // The eviction may have already shrunk the tool result before the summarizer saw it;
        // either way the rendered transcript must not carry the full 5k payload.
        assertFalse(rendered.contains("y".repeat(1_000)));
    }

    @Test
    void microcompactEvictsOldToolResultsBeforeAnySummarizing() {
        // A bulky OLD tool result behind a small recent tail (enough user rounds that a
        // summarize cut would also exist): evicting that one result alone fits the window,
        // so the summarizer never runs.
        List<AiChatMessage> history = new ArrayList<>();
        history.add(AiChatMessage.user("list files"));
        history.add(AiChatMessage.assistantWithTools("",
                List.of(fan.summer.fengyu.ai.AiToolCall.of("tc-1", "execute_command",
                        java.util.Map.of("cmd", "ls")))));
        history.add(AiChatMessage.toolResult("tc-1", "execute_command", "y".repeat(5_000)));
        history.add(AiChatMessage.user("u2 small"));
        history.add(AiChatMessage.assistant("r2"));
        history.add(AiChatMessage.user("u3 small"));
        history.add(AiChatMessage.assistant("r3"));
        history.add(AiChatMessage.user("u4 small"));
        history.add(AiChatMessage.assistant("r4"));
        history.add(AiChatMessage.user("u5 small"));
        history.add(AiChatMessage.assistant("r5"));

        var result = ConversationCompactor.compact(history, 2_000,
                ignored -> fail("microcompact-only fits must not call the summarizer"));

        assertTrue(result.compacted());
        assertTrue(result.microcompacted());
        assertEquals(ConversationCompactor.EVICTED_PLACEHOLDER,
                result.history().get(2).content());
        assertTrue(result.estimatedTokensAfter() < result.estimatedTokensBefore());
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
    void summarizerFailureDegradesToHardTruncationOfTheOldestRounds() {
        // Both summarizer attempts fail (key invalid / provider down): returning the
        // unchanged history would ship a guaranteed over-window payload the provider
        // rejects with a 400. The compactor must instead degrade to dropping the oldest
        // complete rounds — no summary message, recent tail kept verbatim.
        List<AiChatMessage> history = longHistory();
        var result = ConversationCompactor.compact(history, 100,
                ignored -> { throw new IllegalStateException("provider unavailable"); });

        assertTrue(result.compacted(), "degradation is still a (bounded) compaction result");
        assertNotEquals(history, result.history());
        assertTrue(result.history().size() < history.size(),
                "the oldest rounds are hard-truncated away");
        assertTrue(result.estimatedTokensAfter() < result.estimatedTokensBefore());
        // No fabricated summary is injected: what remains is the verbatim recent tail...
        assertTrue(result.history().stream().noneMatch(message -> message.content()
                .startsWith(ConversationCompactor.SUMMARY_PREFIX)));
        // ...starting at a whole-round (user-turn) boundary and keeping the latest exchange.
        assertEquals(AiChatMessage.Role.USER, result.history().getFirst().role());
        assertEquals("u9" + "x".repeat(100), result.history().getFirst().content());
        assertEquals("a10" + "x".repeat(100), result.history().getLast().content());
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

    // ── mid-turn compaction (the live Spring conversation between tool rounds) ──────────

    private static org.springframework.ai.chat.messages.AssistantMessage toolCallMessage(
            String label) {
        return org.springframework.ai.chat.messages.AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                        label + "-id", "function", label, "{}")))
                .build();
    }

    private static org.springframework.ai.chat.messages.ToolResponseMessage toolResponseMessage(
            String label, int resultChars) {
        return org.springframework.ai.chat.messages.ToolResponseMessage.builder()
                .responses(List.of(new org.springframework.ai.chat.messages.ToolResponseMessage
                        .ToolResponse(label + "-id", label, "x".repeat(resultChars))))
                .build();
    }

    /** [system, user, round1, round2, round3, round4] with ~500-token tool results. */
    private static List<org.springframework.ai.chat.messages.Message> toolConversation() {
        List<org.springframework.ai.chat.messages.Message> conversation = new ArrayList<>();
        conversation.add(new org.springframework.ai.chat.messages.SystemMessage("stable system"));
        conversation.add(new org.springframework.ai.chat.messages.UserMessage("initial request"));
        for (int round = 1; round <= 4; round++) {
            conversation.add(toolCallMessage("tool" + round));
            conversation.add(toolResponseMessage("tool" + round, 2_000));
        }
        return conversation;
    }

    @Test
    void tokenScopesSeparateTotalFromTheCachedPrefixBaseline() {
        List<org.springframework.ai.chat.messages.Message> conversation = toolConversation();
        int total = ConversationCompactor.estimateSpringTokens(conversation);

        assertEquals(total, ConversationCompactor.tokenScopes(conversation, 0).totalTokens());
        assertEquals(total, ConversationCompactor.tokenScopes(conversation, 0).afterPrefixTokens(),
                "a zero baseline makes the whole prompt fresh");
        assertEquals(50, ConversationCompactor.tokenScopes(conversation, total - 50)
                .afterPrefixTokens(), "only growth past the baseline is fresh");
        assertEquals(0, ConversationCompactor.tokenScopes(conversation, total + 999)
                .afterPrefixTokens(), "the after-prefix scope never goes negative");
    }

    @Test
    void midTurnCompactionPreservesPrefixSummarizesMiddleKeepsTail() {
        List<org.springframework.ai.chat.messages.Message> conversation = toolConversation();
        org.springframework.ai.chat.messages.Message system = conversation.get(0);
        org.springframework.ai.chat.messages.Message user = conversation.get(1);
        var call3 = conversation.get(6);
        var response4 = conversation.get(9);
        java.util.concurrent.atomic.AtomicReference<String> transcript =
                new java.util.concurrent.atomic.AtomicReference<>();

        // 4 rounds of ~510 tokens ≈ 2.1k total; window 2000 crosses the 85% mid-turn mark.
        var result = ConversationCompactor.compactMidTurn(conversation, 2_000, 0, value -> {
            transcript.set(value);
            return "middle rounds summary";
        });

        assertTrue(result.compacted());
        assertFalse(result.degraded());
        assertSame(system, result.conversation().get(0), "the cacheable system prefix survives");
        assertSame(user, result.conversation().get(1), "the initial request survives verbatim");
        assertTrue(transcript.get().contains("TOOL(tool1)"), "the middle reaches the summarizer");
        assertTrue(transcript.get().contains("TOOL(tool2)"));
        assertFalse(transcript.get().contains("TOOL(tool3)"), "kept rounds are not summarized");

        // Shape: prefix (2) + summary (1) + kept rounds 3-4 (4 messages) = 7.
        assertEquals(7, result.conversation().size());
        var summary = result.conversation().get(2);
        // USER role: a thinking endpoint rejects a replayed assistant summary without
        // reasoning_content, so the host-injected summary must never wear assistant.
        assertTrue(summary instanceof org.springframework.ai.chat.messages.UserMessage);
        assertTrue(summary.getText().startsWith(ConversationCompactor.MID_TURN_SUMMARY_PREFIX));
        assertTrue(summary.getText().contains("middle rounds summary"));
        assertSame(call3, result.conversation().get(3), "kept round 3's call stays paired");
        assertSame(response4, result.conversation().get(6), "kept round 4's result stays last");
        assertTrue(result.after().totalTokens() < result.before().totalTokens());
        assertEquals(result.before().totalTokens(), result.before().afterPrefixTokens(),
                "zero baseline: everything was fresh before the cut");
    }

    @Test
    void belowTheMidTurnThresholdTheConversationStaysUntouched() {
        List<org.springframework.ai.chat.messages.Message> conversation = toolConversation();
        var result = ConversationCompactor.compactMidTurn(conversation, 100_000, 0,
                ignored -> fail("below the threshold the summarizer must not run"));
        assertFalse(result.compacted());
        assertEquals(conversation, result.conversation());
    }

    @Test
    void summarizerFailureDegradesToPrefixPlusTailNeverFailsOpen() {
        List<org.springframework.ai.chat.messages.Message> conversation = toolConversation();
        var result = ConversationCompactor.compactMidTurn(conversation, 2_000, 0, value -> {
            throw new IllegalStateException("provider down");
        });
        assertTrue(result.compacted());
        assertTrue(result.degraded());
        assertEquals(6, result.conversation().size(),
                "prefix (2) + kept rounds 3-4 (4) — the middle is dropped, nothing else");
        assertTrue(result.after().totalTokens() < result.before().totalTokens());
    }

    @Test
    void withoutToolRoundsOrWithOnlyKeepWindowRoundsNothingIsCut() {
        var plain = List.<org.springframework.ai.chat.messages.Message>of(
                new org.springframework.ai.chat.messages.SystemMessage("sys"),
                new org.springframework.ai.chat.messages.UserMessage("hi"),
                org.springframework.ai.chat.messages.AssistantMessage.builder()
                        .content("plain answer").build());
        assertFalse(ConversationCompactor.compactMidTurn(plain, 100, 0,
                ignored -> fail("no tool rounds means no mid-turn cut")).compacted());

        List<org.springframework.ai.chat.messages.Message> oneRound = new ArrayList<>();
        oneRound.add(new org.springframework.ai.chat.messages.SystemMessage("sys"));
        oneRound.add(new org.springframework.ai.chat.messages.UserMessage("hi"));
        oneRound.add(toolCallMessage("only"));
        oneRound.add(toolResponseMessage("only", 4_000));
        assertFalse(ConversationCompactor.compactMidTurn(oneRound, 100, 0,
                ignored -> fail("a single round is entirely inside the keep window"))
                .compacted());
    }

    // ── preflight output clamp ─────────────────────────────────────────────────────

    @Test
    void outputClampShrinksWithHeadroomButNeverBelowUsable() {
        // Fresh conversation: full model cap affordable.
        assertEquals(128_000,
                ConversationCompactor.clampMaxOutputTokens(128_000, 200_000, 10_000));
        // Near the window: budget = window − input − reserve.
        assertEquals(200_000 - 150_000 - 1_000,
                ConversationCompactor.clampMaxOutputTokens(128_000, 200_000, 150_000));
        // Small model cap stays untouched when headroom is plentiful.
        assertEquals(8_192,
                ConversationCompactor.clampMaxOutputTokens(8_192, 32_768, 5_000));
    }

    @Test
    void outputClampKeepsBaselineWhenEstimatesAreUnusable() {
        // No positive headroom left: the local estimate is not authoritative — keep the
        // baseline and let the provider's own rejection drive the recovery paths.
        assertEquals(128_000,
                ConversationCompactor.clampMaxOutputTokens(128_000, 200_000, 199_500));
        // Compaction off (window 0) / unknown cap: nothing to clamp against.
        assertEquals(64_000, ConversationCompactor.clampMaxOutputTokens(64_000, 0, 10_000));
        assertEquals(0, ConversationCompactor.clampMaxOutputTokens(0, 200_000, 10_000));
    }
}
