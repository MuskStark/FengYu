package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiMedia;
import fan.summer.fengyu.ai.AiToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch-A transcript normalization rules (see docs/plans/multi-provider-refactor.md §4).
 * The zero-regression contract — same-model, wire-valid, fully-answered histories pass
 * through with IDENTICAL instances — is pinned by the pass-through tests.
 */
class TranscriptNormalizerTest {

    private static final String ORIGIN_A = "OPENAI/gpt-4o";
    private static final String ORIGIN_B = "ANTHROPIC/claude-sonnet-4-5";

    private static TranscriptNormalizer.Target target(String origin, Boolean image) {
        return new TranscriptNormalizer.Target(origin, image);
    }

    // ── A1: reasoning is same-origin only ─────────────────────────────────────

    @Test
    void sameOriginReasoningIsKeptVerbatim() {
        AiChatMessage msg = new AiChatMessage(AiChatMessage.Role.ASSISTANT, "text",
                List.of(), null, null, "chain-of-thought", List.of(), ORIGIN_A);
        List<AiChatMessage> out = TranscriptNormalizer.normalize(List.of(msg), target(ORIGIN_A, null));
        assertSame(msg, out.get(0), "same-origin message must pass through untouched");
    }

    @Test
    void crossOriginReasoningIsStripped() {
        AiChatMessage msg = new AiChatMessage(AiChatMessage.Role.ASSISTANT, "text",
                List.of(), null, null, "chain-of-thought", List.of(), ORIGIN_B);
        List<AiChatMessage> out = TranscriptNormalizer.normalize(List.of(msg), target(ORIGIN_A, null));
        assertNull(out.get(0).reasoningContent());
        assertEquals("text", out.get(0).content());
        assertEquals(ORIGIN_B, out.get(0).origin(), "origin attribution survives the strip");
    }

    @Test
    void legacyUnattributedReasoningIsKept() {
        // Persisted history predating origin stamping must keep today's replay behavior.
        AiChatMessage msg = new AiChatMessage(AiChatMessage.Role.ASSISTANT, "text",
                List.of(), null, null, "chain-of-thought", List.of(), null);
        List<AiChatMessage> out = TranscriptNormalizer.normalize(List.of(msg), target(ORIGIN_A, null));
        assertSame(msg, out.get(0));
    }

    // ── A2: tool-call IDs are wire-valid ─────────────────────────────────────

    @Test
    void wireValidIdsPassThroughUntouched() {
        AiToolCall call = AiToolCall.of("toolu_01ABCdef_123", "read_file", Map.of());
        AiChatMessage assistant = new AiChatMessage(AiChatMessage.Role.ASSISTANT, "",
                List.of(call), null, null, null, List.of(), ORIGIN_A);
        AiChatMessage result = AiChatMessage.toolResult("toolu_01ABCdef_123", "read_file", "ok");
        List<AiChatMessage> out = TranscriptNormalizer.normalize(List.of(assistant, result),
                target(ORIGIN_A, null));
        assertSame(assistant, out.get(0));
        assertSame(result, out.get(1));
    }

    @Test
    void invalidIdsAreSanitizedAndResultsRemapped() {
        String longId = "call_" + "a".repeat(200) + "|pipe|chars!";
        AiToolCall call = AiToolCall.of(longId, "read_file", Map.of());
        AiChatMessage assistant = new AiChatMessage(AiChatMessage.Role.ASSISTANT, "",
                List.of(call), null, null, null, List.of(), ORIGIN_A);
        AiChatMessage result = AiChatMessage.toolResult(longId, "read_file", "ok");
        List<AiChatMessage> out = TranscriptNormalizer.normalize(List.of(assistant, result),
                target(ORIGIN_B, null));
        String sanitized = out.get(0).toolCalls().get(0).id();
        assertTrue(sanitized.matches("[A-Za-z0-9_-]{1,64}"), "sanitized: " + sanitized);
        assertEquals(sanitized, out.get(1).toolCallId(), "tool result must follow the remap");
        assertEquals("read_file", out.get(0).toolCalls().get(0).name());
    }

    @Test
    void sanitizedIdCollisionsGetUniqueSuffixes() {
        String first = "call_x|y";
        String second = "call_x;y"; // same wire shape after sanitation
        AiChatMessage assistant = new AiChatMessage(AiChatMessage.Role.ASSISTANT, "",
                List.of(AiToolCall.of(first, "a", Map.of()), AiToolCall.of(second, "b", Map.of())),
                null, null, null, List.of(), ORIGIN_A);
        List<AiChatMessage> out = TranscriptNormalizer.normalize(List.of(assistant,
                AiChatMessage.toolResult(first, "a", "1"), AiChatMessage.toolResult(second, "b", "2")),
                target(ORIGIN_A, null));
        String id1 = out.get(0).toolCalls().get(0).id();
        String id2 = out.get(0).toolCalls().get(1).id();
        assertTrue(!id1.equals(id2), "colliding sanitized ids must be deduped");
        assertEquals(id1, out.get(1).toolCallId());
        assertEquals(id2, out.get(2).toolCallId());
    }

    // ── A3: orphaned tool calls get synthetic results ────────────────────────

    @Test
    void orphanedCallBeforeUserTurnGetsSyntheticResult() {
        AiChatMessage assistant = AiChatMessage.assistantWithTools("",
                List.of(AiToolCall.of("call_1", "read_file", Map.of())));
        AiChatMessage user = AiChatMessage.user("next question");
        List<AiChatMessage> out = TranscriptNormalizer.normalize(
                List.of(assistant, user), target(ORIGIN_A, null));
        assertEquals(3, out.size());
        assertEquals(AiChatMessage.Role.TOOL, out.get(1).role());
        assertEquals("call_1", out.get(1).toolCallId());
        assertEquals(TranscriptNormalizer.NO_RESULT, out.get(1).content());
        assertEquals(AiChatMessage.Role.USER, out.get(2).role());
    }

    @Test
    void orphanedTrailingCallGetsSyntheticResult() {
        AiChatMessage assistant = AiChatMessage.assistantWithTools("",
                List.of(AiToolCall.of("call_1", "read_file", Map.of())));
        List<AiChatMessage> out = TranscriptNormalizer.normalize(List.of(assistant),
                target(ORIGIN_A, null));
        assertEquals(2, out.size());
        assertEquals(TranscriptNormalizer.NO_RESULT, out.get(1).content());
    }

    @Test
    void answeredCallsGetNoSyntheticResult() {
        AiChatMessage assistant = AiChatMessage.assistantWithTools("",
                List.of(AiToolCall.of("call_1", "read_file", Map.of())));
        AiChatMessage result = AiChatMessage.toolResult("call_1", "read_file", "ok");
        List<AiChatMessage> out = TranscriptNormalizer.normalize(List.of(assistant, result),
                target(ORIGIN_A, null));
        assertEquals(2, out.size(), "a fully answered round must not gain messages");
        assertSame(result, out.get(1));
    }

    @Test
    void systemBetweenCallAndResultDoesNotOrphanIt() {
        AiChatMessage assistant = AiChatMessage.assistantWithTools("",
                List.of(AiToolCall.of("call_1", "read_file", Map.of())));
        AiChatMessage system = AiChatMessage.system("mid instructions");
        AiChatMessage result = AiChatMessage.toolResult("call_1", "read_file", "ok");
        List<AiChatMessage> out = TranscriptNormalizer.normalize(
                List.of(assistant, system, result), target(ORIGIN_A, null));
        assertEquals(3, out.size(), "held system message is re-emitted after its results");
        // Held system messages re-emit AFTER the results so the wire never places a
        // system message between a tool call and its results.
        assertEquals(AiChatMessage.Role.TOOL, out.get(1).role());
        assertEquals(AiChatMessage.Role.SYSTEM, out.get(2).role());
    }

    // ── A4: media downgrade on exact non-vision assertion ────────────────────

    @Test
    void mediaDowngradedWhenTargetAssertedNonVision() {
        AiMedia img = new AiMedia("image/png", "aGVsbG8=", "screenshot.png");
        AiChatMessage user = AiChatMessage.userWithMedia("look at this", List.of(img));
        AiChatMessage result = AiChatMessage.toolResult("call_1", "browser", "done", List.of(img));
        List<AiChatMessage> out = TranscriptNormalizer.normalize(List.of(user, result),
                target(ORIGIN_A, false));
        assertTrue(out.get(0).media().isEmpty());
        assertTrue(out.get(0).content().contains(TranscriptNormalizer.IMAGE_PLACEHOLDER));
        assertTrue(out.get(0).content().startsWith("look at this"));
        assertTrue(out.get(1).media().isEmpty());
    }

    @Test
    void mediaKeptWhenTargetVisionOrUnknown() {
        AiMedia img = new AiMedia("image/png", "aGVsbG8=", "screenshot.png");
        AiChatMessage user = AiChatMessage.userWithMedia("look at this", List.of(img));
        assertSame(user, TranscriptNormalizer.normalize(List.of(user), target(ORIGIN_A, true)).get(0));
        assertSame(user, TranscriptNormalizer.normalize(List.of(user), target(ORIGIN_A, null)).get(0));
    }

    // ── pass-through contract on a realistic same-model history ──────────────

    @Test
    void realisticSameModelHistoryPassesThroughIdentical() {
        AiChatMessage system = AiChatMessage.system("sys");
        AiChatMessage user1 = AiChatMessage.user("read the file");
        AiChatMessage toolRound = new AiChatMessage(AiChatMessage.Role.ASSISTANT, "thinking...",
                List.of(AiToolCall.of("call_1", "read_file", Map.of("path", "a.txt"))),
                null, null, "because", List.of(), ORIGIN_A);
        AiChatMessage toolResult = AiChatMessage.toolResult("call_1", "read_file", "contents");
        AiChatMessage finalAnswer = AiChatMessage.assistant("here is the answer");
        AiChatMessage user2 = AiChatMessage.user("thanks");
        List<AiChatMessage> history = List.of(system, user1, toolRound, toolResult, finalAnswer, user2);
        List<AiChatMessage> out = TranscriptNormalizer.normalize(history, target(ORIGIN_A, null));
        assertEquals(history.size(), out.size());
        for (int i = 0; i < history.size(); i++) {
            assertSame(history.get(i), out.get(i), "index " + i + " must be untouched");
        }
    }
}
