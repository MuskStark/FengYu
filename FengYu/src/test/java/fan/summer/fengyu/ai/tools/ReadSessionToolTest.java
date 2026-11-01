package fan.summer.fengyu.ai.tools;

import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiToolCall;
import fan.summer.fengyu.ai.session.AiRolloutService;
import fan.summer.fengyu.database.entity.ai.ChatMessageEntity;
import fan.summer.fengyu.database.entity.ai.ConversationEntity;
import fan.summer.fengyu.database.repository.ai.ChatMessageRepository;
import fan.summer.fengyu.database.repository.ai.ConversationRepository;
import fan.summer.fengyu.security.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The cross-session read tool, fully isolated (mocked repositories + a temp rollout root —
 * no DB, no network): the list phase (sidebar ordering, rollout-based message estimates,
 * current/archived markers, list-then-read guidance), the read phase (tail-windowed
 * transcript rebuilt from the rollout log, title header, system-message elision, tool-call
 * summaries), the per-message 2000-char truncation, the saved-DB fallback when no rollout
 * log exists, the lastMessages default/cap/floor boundaries, and the ownership gate that
 * holds even when a rollout log for the id exists on disk.
 */
class ReadSessionToolTest {

    private static final Long CONVERSATION = 77L;
    private static final Long USER = 1L;

    @TempDir
    Path root;

    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final SecurityContext security = mock(SecurityContext.class);

    private AiRolloutService rollouts;
    private ReadSessionTool tool;

    @BeforeEach
    void setUp() {
        rollouts = new AiRolloutService(root);
        when(security.currentUserId()).thenReturn(USER);
        tool = new ReadSessionTool(rollouts, conversations, messages, security);
    }

    @AfterEach
    void cleanup() {
        ConversationContext.clear();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────

    private static ConversationEntity conversation(long id, String title) {
        ConversationEntity entity = new ConversationEntity();
        entity.setId(id);
        entity.setTitle(title);
        entity.setCreatedAt(LocalDateTime.of(2026, 10, 1, 9, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 10, 30, 14, 22));
        return entity;
    }

    private static ChatMessageEntity savedMessage(long conversationId, int seq,
            String role, String content) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setConversationId(conversationId);
        entity.setSeq(seq);
        entity.setRole(role);
        entity.setContent(content);
        return entity;
    }

    /** A realistic coding turn in the log: system noise + user + tool round-trip. */
    private void recordCodingTurn(Long conversationId) {
        AiRolloutService.Recorder recorder = rollouts.start(conversationId, "openai", "test-model");
        recorder.message(AiChatMessage.system("you are a coding agent"));
        recorder.message(AiChatMessage.user("fix the bug in Service.java"));
        recorder.message(AiChatMessage.assistantWithTools("",
                List.of(AiToolCall.of("call-1", "grep", Map.of("pattern", "Service")))));
        recorder.message(AiChatMessage.toolResult("call-1", "grep",
                "{\"matches\":[\"Service.java:9\"]}"));
        recorder.message(AiChatMessage.assistant("done, the null check was missing"));
        recorder.end("complete", 42);
    }

    private void own(Long conversationId, String title) {
        when(conversations.findByIdAndUserId(conversationId, USER))
                .thenReturn(Optional.of(conversation(conversationId, title)));
    }

    // ── list phase ───────────────────────────────────────────────────────────────────────

    @Test
    void listModeShowsRecentSessionsWithRolloutEstimatesAndReadGuidance() {
        recordCodingTurn(12L); // 5 message events recorded, system included
        when(conversations.findByUserIdOrderByUpdatedAtDesc(USER)).thenReturn(List.of(
                conversation(12, "Fix the login bug"),
                conversation(7, "Plan the migration")));

        String result = tool.readSession(null, null);

        assertTrue(result.contains("call read_session with its conversationId"), result);
        int first = result.indexOf("- id 12");
        int second = result.indexOf("- id 7");
        assertTrue(first >= 0 && second > first, "sidebar order is newest-updated first:\n" + result);
        assertTrue(result.contains("Fix the login bug"), result);
        assertTrue(result.contains("updated 2026-10-30T14:22"), result);
        assertTrue(result.contains("~5 msgs"), "rollout message events counted:\n" + result);
        assertTrue(result.contains("~0 msgs"),
                "a session without a rollout log estimates zero, not an error:\n" + result);
    }

    @Test
    void listModeMarksArchivedAndTheCurrentConversation() {
        ConversationEntity archived = conversation(30, "Old work");
        archived.setArchivedAt(LocalDateTime.of(2026, 9, 1, 0, 0));
        when(conversations.findByUserIdOrderByUpdatedAtDesc(USER))
                .thenReturn(List.of(conversation(12, "Current"), archived));
        ConversationContext.set(12L);

        String result = tool.readSession(null, null);

        String currentLine = result.lines().filter(l -> l.startsWith("- id 12")).findFirst().orElse("");
        String archivedLine = result.lines().filter(l -> l.startsWith("- id 30")).findFirst().orElse("");
        assertTrue(currentLine.endsWith("| current conversation"), result);
        assertTrue(archivedLine.endsWith("| archived"), result);
    }

    @Test
    void listModeCapsAtTwentyEntries() {
        ConversationEntity[] many = java.util.stream.IntStream.rangeClosed(1, 25)
                .mapToObj(i -> conversation(i, "session " + i))
                .toArray(ConversationEntity[]::new);
        when(conversations.findByUserIdOrderByUpdatedAtDesc(USER)).thenReturn(List.of(many));

        String result = tool.readSession(null, null);

        assertTrue(result.contains("- id 20"), result);
        assertFalse(result.contains("- id 21"), "only the 20 most recent are listed:\n" + result);
        assertTrue(result.contains("25 sessions exist"), result);
    }

    @Test
    void listModeWithoutAnySessionAnswersHonestly() {
        when(conversations.findByUserIdOrderByUpdatedAtDesc(USER)).thenReturn(List.of());

        String result = tool.readSession(null, null);

        assertTrue(result.contains("No past sessions found"), result);
    }

    // ── read phase: rollout source ───────────────────────────────────────────────────────

    @Test
    void readModeRebuildsTailTranscriptFromTheRolloutLog() {
        own(CONVERSATION, "Fix the login bug");
        recordCodingTurn(CONVERSATION);

        String result = tool.readSession(CONVERSATION, null);

        assertTrue(result.startsWith("Session 77: Fix the login bug"),
                "the session title is the first line:\n" + result);
        assertTrue(result.contains("Showing the last 4 of 4 messages (source: rollout log)"),
                "system boilerplate is elided (5 recorded - 1 system), tool traffic is kept:\n"
                        + result);
        assertFalse(result.contains("system: "), result);
        assertTrue(result.contains("user: fix the bug in Service.java"), result);
        assertTrue(result.contains("assistant: [tool_call: grep]"),
                "a blank assistant message renders as its call summary:\n" + result);
        assertTrue(result.contains("tool(grep): {\"matches\":[\"Service.java:9\"]}"), result);
        assertTrue(result.contains("assistant: done, the null check was missing"), result);
    }

    @Test
    void readModeWindowsTheTailOfLongSessions() {
        own(CONVERSATION, "long session");
        AiRolloutService.Recorder recorder = rollouts.start(CONVERSATION, "openai", "m");
        for (int i = 1; i <= 10; i++) recorder.message(AiChatMessage.user("message " + i));
        recorder.end("complete", 0);

        String result = tool.readSession(CONVERSATION, 3);

        assertTrue(result.contains("Showing the last 3 of 10 messages"), result);
        assertTrue(result.contains("user: message 10"), result);
        assertFalse(result.contains("user: message 7"),
                "only the requested trailing window is shown:\n" + result);
    }

    @Test
    void oversizedContentIsTruncatedToTwoThousandCharacters() {
        own(CONVERSATION, "big paste");
        AiRolloutService.Recorder recorder = rollouts.start(CONVERSATION, "openai", "m");
        recorder.message(AiChatMessage.user("x".repeat(3_000)));
        recorder.end("complete", 0);

        String result = tool.readSession(CONVERSATION, null);

        String line = result.lines().filter(l -> l.startsWith("user: ")).findFirst().orElse("");
        assertTrue(line.endsWith(ReadSessionTool.TRUNCATION_SUFFIX), result);
        assertEquals("user: ".length() + 2_000 + ReadSessionTool.TRUNCATION_SUFFIX.length(),
                line.length(), "the cut body is exactly MESSAGE_CAP characters:\n" + result);
    }

    // ── read phase: DB fallback ──────────────────────────────────────────────────────────

    @Test
    void missingRolloutFallsBackToSavedDbMessages() {
        own(CONVERSATION, "legacy session"); // no rollout file under the temp root
        when(messages.findByConversationIdOrderBySeqAsc(CONVERSATION)).thenReturn(List.of(
                savedMessage(CONVERSATION, 0, "user", "what did we decide about the cache?"),
                savedMessage(CONVERSATION, 1, "assistant", "LRU with a 5-minute TTL"),
                savedMessage(CONVERSATION, 2, "user", "and the retry policy?")));

        String result = tool.readSession(CONVERSATION, 2);

        assertTrue(result.contains("source: saved DB messages (no rollout log)"), result);
        assertTrue(result.contains("Showing the last 2 of 3 messages"), result);
        assertTrue(result.contains("assistant: LRU with a 5-minute TTL"), result);
        assertTrue(result.contains("user: and the retry policy?"), result);
        assertFalse(result.contains("what did we decide about the cache?"),
                "the window applies to the fallback source too:\n" + result);
    }

    @Test
    void sessionWithNoReadableMessagesAnywhereAnswersHonestly() {
        own(CONVERSATION, "empty session");
        when(messages.findByConversationIdOrderBySeqAsc(CONVERSATION)).thenReturn(List.of());

        String result = tool.readSession(CONVERSATION, null);

        assertTrue(result.contains("has no readable messages"), result);
    }

    // ── lastMessages boundaries ──────────────────────────────────────────────────────────

    @Test
    void lastMessagesDefaultsToTwenty() {
        own(CONVERSATION, "default window");
        AiRolloutService.Recorder recorder = rollouts.start(CONVERSATION, "openai", "m");
        for (int i = 1; i <= 25; i++) recorder.message(AiChatMessage.user("m" + i));
        recorder.end("complete", 0);

        String result = tool.readSession(CONVERSATION, null);

        assertTrue(result.contains("Showing the last 20 of 25 messages"), result);
        assertTrue(result.contains("user: m25"), result);
        assertFalse(result.contains("user: m5"), result);
    }

    @Test
    void lastMessagesAboveOneHundredIsClampedWithANote() {
        own(CONVERSATION, "clamped window");
        AiRolloutService.Recorder recorder = rollouts.start(CONVERSATION, "openai", "m");
        for (int i = 1; i <= 150; i++) recorder.message(AiChatMessage.user("m" + i));
        recorder.end("complete", 0);

        String result = tool.readSession(CONVERSATION, 500);

        assertTrue(result.contains("Showing the last 100 of 150 messages"), result);
        assertTrue(result.contains("capped at 100"), result);
        assertFalse(result.contains("user: m50"), result);
    }

    @Test
    void lastMessagesBelowOneIsRejectedWithGuidance() {
        String result = tool.readSession(CONVERSATION, 0);

        assertTrue(result.contains("lastMessages must be between 1 and 100"), result);
        assertTrue(result.contains("default of 20"), result);
    }

    // ── ownership gate ───────────────────────────────────────────────────────────────────

    @Test
    void unknownConversationIdAnswersListFirstGuidance() {
        when(conversations.findByIdAndUserId(404L, USER)).thenReturn(Optional.empty());

        String result = tool.readSession(404L, null);

        assertTrue(result.contains("was not found among your sessions"), result);
        assertTrue(result.contains("Call read_session without conversationId"), result);
    }

    @Test
    void ownershipGateHoldsEvenWhenARolloutLogExistsForTheId() {
        // A log for 88 exists on disk, but the DB says 88 is not this user's conversation:
        // rollout paths carry no user component, so the DB check is the only gate.
        recordCodingTurn(88L);
        when(conversations.findByIdAndUserId(88L, USER)).thenReturn(Optional.empty());

        String result = tool.readSession(88L, null);

        assertTrue(result.contains("was not found among your sessions"), result);
        assertFalse(result.contains("fix the bug in Service.java"),
                "a foreign session's log must never leak:\n" + result);
    }
}
