package fan.summer.fengyu.ai.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiToolCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server-side rollout log: JSONL append shape (turn boundaries, messages with tool
 * calls, capped raw tool results, compaction events), resume rebuild from the record,
 * fork isolation (a fork log is a copy of the prefix under a provenance header — later
 * fork writes never touch the source), and crash-torn tail tolerance.
 */
class AiRolloutServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Long CONVERSATION = 77L;

    @TempDir
    Path root;

    private AiRolloutService service;
    private Path file;

    @BeforeEach
    void setUp() {
        service = new AiRolloutService(root);
        file = root.resolve("77").resolve("rollout.jsonl");
    }

    private AiRolloutService.Recorder recordTurn(String provider) {
        AiRolloutService.Recorder recorder = service.start(CONVERSATION, provider, "test-model");
        recorder.message(AiChatMessage.user("fix the bug in Service.java"));
        recorder.message(AiChatMessage.assistantWithTools("",
                List.of(AiToolCall.of("call-1", "grep", Map.of("pattern", "Service")))));
        recorder.toolResult("call-1", "grep", "{\"matches\":[\"Service.java:9\"]}", true);
        recorder.message(AiChatMessage.toolResult("call-1", "grep",
                "{\"matches\":[\"Service.java:9\"]}"));
        recorder.compaction("mid_turn", 2_000, 1_200, 2_000, 400, false, false);
        recorder.end("complete", 42);
        return recorder;
    }

    // ── record write shape ───────────────────────────────────────────────────────────────

    @Test
    void jsonlAppendShapeCoversTheWholeTurn() throws Exception {
        recordTurn("openai");

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(7, lines.size());

        Map<String, Object> start = JSON.readValue(lines.get(0), Map.class);
        assertEquals("turn_start", start.get("type"));
        assertEquals("openai", start.get("provider"));
        assertEquals("test-model", start.get("model"));
        assertEquals(1, ((Number) start.get("seq")).intValue());

        Map<String, Object> user = JSON.readValue(lines.get(1), Map.class);
        assertEquals("message", user.get("type"));
        assertEquals("USER", user.get("role"));
        assertEquals("fix the bug in Service.java", user.get("content"));

        Map<String, Object> assistant = JSON.readValue(lines.get(2), Map.class);
        assertEquals("ASSISTANT", assistant.get("role"));
        List<Map<String, Object>> calls = (List<Map<String, Object>>) assistant.get("toolCalls");
        assertEquals("grep", calls.get(0).get("name"));
        assertEquals("call-1", calls.get(0).get("id"));

        Map<String, Object> raw = JSON.readValue(lines.get(3), Map.class);
        assertEquals("tool_result", raw.get("type"));
        assertEquals("call-1", raw.get("callId"));
        assertEquals(Boolean.TRUE, raw.get("success"));

        Map<String, Object> toolMessage = JSON.readValue(lines.get(4), Map.class);
        assertEquals("TOOL", toolMessage.get("role"));
        assertEquals("grep", toolMessage.get("toolName"));
        assertEquals("call-1", toolMessage.get("toolCallId"));

        Map<String, Object> compaction = JSON.readValue(lines.get(5), Map.class);
        assertEquals("compaction", compaction.get("type"));
        assertEquals("mid_turn", compaction.get("phase"));
        assertEquals(2_000, ((Number) compaction.get("tokensBefore")).intValue());
        assertEquals(400, ((Number) compaction.get("afterPrefixAfter")).intValue());

        Map<String, Object> end = JSON.readValue(lines.get(6), Map.class);
        assertEquals("turn_end", end.get("type"));
        assertEquals("complete", end.get("reason"));
        assertEquals(42, ((Number) end.get("completionTokens")).intValue());

        // seq is per-FILE monotonic: a second turn continues the numbering.
        AiRolloutService.Recorder second = service.start(CONVERSATION, "openai", "test-model");
        second.message(AiChatMessage.user("and now?"));
        second.end("complete", 5);
        Map<String, Object> nextStart = JSON.readValue(
                Files.readAllLines(file, StandardCharsets.UTF_8).get(7), Map.class);
        assertEquals(8, ((Number) nextStart.get("seq")).intValue());
    }

    @Test
    void oversizedToolResultsAreCappedInTheLog() {
        AiRolloutService.Recorder recorder = service.start(CONVERSATION, "openai", "m");
        recorder.toolResult("c1", "read_file", "x".repeat(50_000), true);
        recorder.end("complete", 0);
        assertTrue(service.events(CONVERSATION).get(1).get("output").toString().endsWith("…[capped]"));
    }

    // ── resume rebuild ───────────────────────────────────────────────────────────────────

    @Test
    void rebuildReplaysMessagesWithToolPairing() {
        recordTurn("openai");

        List<AiChatMessage> rebuilt = service.rebuild(CONVERSATION, Long.MAX_VALUE);
        assertEquals(3, rebuilt.size(),
                "only message events replay: turn_start/tool_result/compaction/turn_end do not");
        assertEquals(AiChatMessage.Role.USER, rebuilt.get(0).role());
        assertEquals("fix the bug in Service.java", rebuilt.get(0).content());
        assertEquals(AiChatMessage.Role.ASSISTANT, rebuilt.get(1).role());
        assertEquals(1, rebuilt.get(1).toolCalls().size());
        assertEquals("grep", rebuilt.get(1).toolCalls().getFirst().name());
        assertEquals("call-1", rebuilt.get(1).toolCalls().getFirst().id());
        assertEquals(AiChatMessage.Role.TOOL, rebuilt.get(2).role());
        assertEquals("call-1", rebuilt.get(2).toolCallId());
        assertEquals("grep", rebuilt.get(2).toolName());
    }

    @Test
    void rebuildUpToSeqReplaysOnlyThePrefix() {
        recordTurn("openai");
        // seq 1=turn_start, 2=user, 3=assistant-with-tools, 4=raw, 5=tool msg, 6=compaction, 7=end
        List<AiChatMessage> rebuilt = service.rebuild(CONVERSATION, 3);
        assertEquals(2, rebuilt.size());
        assertEquals(AiChatMessage.Role.USER, rebuilt.get(0).role());
        assertEquals(AiChatMessage.Role.ASSISTANT, rebuilt.get(1).role());
    }

    // ── fork isolation ───────────────────────────────────────────────────────────────────

    @Test
    void forkCopiesThePrefixAndNeverTouchesTheSourceAgain() throws Exception {
        recordTurn("openai");
        byte[] sourceBefore = Files.readAllBytes(file);

        assertTrue(service.writeFork(CONVERSATION, 5, 99L));
        Path forkFile = root.resolve("99").resolve("rollout.jsonl");
        List<String> forkLines = Files.readAllLines(forkFile, StandardCharsets.UTF_8);

        Map<String, Object> header = JSON.readValue(forkLines.get(0), Map.class);
        assertEquals("fork", header.get("type"));
        assertEquals(77, ((Number) header.get("source")).intValue());
        assertEquals(5L, ((Number) header.get("upToSeq")).longValue());
        assertEquals(6, forkLines.size(), "header + the five prefix events");
        assertArrayEquals(sourceBefore, Files.readAllBytes(file),
                "the source log is byte-identical after forking");

        // The fork continues its own sequence: start() counts the copied lines.
        AiRolloutService.Recorder forkTurn = service.start(99L, "openai", "m");
        forkTurn.message(AiChatMessage.user("take a different approach"));
        forkTurn.end("complete", 1);
        List<String> afterForkWrites = Files.readAllLines(forkFile, StandardCharsets.UTF_8);
        assertEquals(9, afterForkWrites.size(),
                "6 copied lines + the fork turn's turn_start/message/turn_end");
        Map<String, Object> turnStart = JSON.readValue(afterForkWrites.get(6), Map.class);
        assertEquals(7, ((Number) turnStart.get("seq")).intValue());
        assertArrayEquals(sourceBefore, Files.readAllBytes(file),
                "writing to the fork still never touches the source");

        // A fork of a fork-less conversation is a clean false.
        assertFalse(service.writeFork(12345L, 5, 100L));
    }

    // ── crash resilience ─────────────────────────────────────────────────────────────────

    @Test
    void tornTailLineIsSkippedNotFatal() throws Exception {
        recordTurn("openai");
        Files.writeString(file, "{\"seq\":8,\"type\":\"mess",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        List<Map<String, Object>> events = service.events(CONVERSATION);
        assertEquals(7, events.size(), "the torn 8th line is skipped");

        List<AiChatMessage> rebuilt = service.rebuild(CONVERSATION, Long.MAX_VALUE);
        assertEquals(3, rebuilt.size(), "replay tolerates the torn tail too");
    }

    // ── typed event model ───────────────────────────────────────────────────────────────

    @Test
    void typedEventsRoundTripToTheSealedHierarchy() {
        recordTurn("openai");

        List<RolloutEvent> typed = service.typedEvents(CONVERSATION);
        assertEquals(7, typed.size());
        assertInstanceOf(RolloutEvent.TurnStarted.class, typed.get(0));
        assertEquals("openai", ((RolloutEvent.TurnStarted) typed.get(0)).provider());
        assertInstanceOf(RolloutEvent.MessageAppended.class, typed.get(1));
        assertEquals(AiChatMessage.Role.USER, ((RolloutEvent.MessageAppended) typed.get(1)).message().role());
        assertInstanceOf(RolloutEvent.ToolResultRecorded.class, typed.get(3));
        assertInstanceOf(RolloutEvent.CompactionApplied.class, typed.get(5));
        assertInstanceOf(RolloutEvent.TurnEnded.class, typed.get(6));
        assertEquals("complete", ((RolloutEvent.TurnEnded) typed.get(6)).reason());
    }

    @Test
    void futureEventTypeAndUnknownRoleFallBackToUnknownAndAreSkippedByRebuild() throws Exception {
        recordTurn("openai");
        // A newer log version writes event types / message roles this build does not know.
        Files.writeString(file, "{\"seq\":8,\"type\":\"cell_started\",\"cellId\":\"c-9\"}\n"
                        + "{\"seq\":9,\"type\":\"message\",\"role\":\"FUTURE_ROLE\",\"content\":\"?\"}\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        List<RolloutEvent> typed = service.typedEvents(CONVERSATION);
        assertEquals(9, typed.size());
        assertInstanceOf(RolloutEvent.Unknown.class, typed.get(7));
        assertEquals("cell_started", typed.get(7).type(), "the raw type string is preserved");
        assertInstanceOf(RolloutEvent.Unknown.class, typed.get(8));

        List<AiChatMessage> rebuilt = service.rebuild(CONVERSATION, Long.MAX_VALUE);
        assertEquals(3, rebuilt.size(), "unknown events never enter the replay view");
    }

    @Test
    void recorderSurvivesAnUnwritableRoot() throws Exception {
        AiRolloutService broken = new AiRolloutService(
                root.resolve("file-as-root").resolve("x")); // root path collides below
        Files.writeString(root.resolve("file-as-root"), "in the way",
                StandardCharsets.UTF_8);
        AiRolloutService.Recorder recorder = broken.start(CONVERSATION, "openai", "m");
        // createDirectories fails → recorder disables itself; every call is a silent no-op.
        recorder.message(AiChatMessage.user("hi"));
        recorder.end("complete", 0);
        assertTrue(broken.events(CONVERSATION).isEmpty());
    }
}
