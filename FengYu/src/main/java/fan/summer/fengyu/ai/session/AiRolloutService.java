package fan.summer.fengyu.ai.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.ai.AiChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side session recording — the rollout log of terminal coding agents. Every chat
 * turn over a conversation is APPENDED to {@code <root>/<conversationId>/rollout.jsonl}:
 * one JSON object per line, in order — turn boundaries, each message the model saw or
 * produced (including tool calls), raw tool results, and compaction events. The client
 * transcript stays the UI's business; this log is the server's own memory of what
 * actually happened, so a crashed client, a lost PUT, or a post-hoc "what did the agent
 * do" question can be answered from disk.
 *
 * <p>Events are typed ({@link RolloutEvent} sealed hierarchy) and the append path is
 * single: every recorder method funnels into {@link Recorder#append(RolloutEvent)},
 * which applies the envelope (seq/ts/conversationId) and writes the line. The wire
 * format is unchanged — pre-typing logs replay identically, and lines this build cannot
 * interpret deserialize as {@link RolloutEvent.Unknown} and are skipped.</p>
 *
 * <p>Two operations build on the log: <b>resume</b> rebuilds the FengYu message list from
 * the recorded events (replay of {@code message} events only — tool traffic pairs back
 * into assistant-with-tools + tool-result messages), and <b>fork</b> copies the events up
 * to a sequence number into a NEW conversation's log under a provenance header. A fork's
 * file is its own: later turns on the fork never touch the source log.</p>
 *
 * <p>Recording must never break a turn: every recorder method swallows IO failures and
 * disables itself — a full disk costs the log, not the chat.</p>
 */
@Component
public class AiRolloutService {

    private static final Logger log = LoggerFactory.getLogger(AiRolloutService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Raw tool results are capped per event — a log, not a file dump. */
    static final int TOOL_OUTPUT_CAP = 8_000;
    /** Upper bound on events returned / replayed per conversation. */
    static final int MAX_EVENTS = 5_000;

    private final Path root;

    public AiRolloutService() {
        this(Path.of(System.getProperty("user.home"), ".fengyu", "rollouts"));
    }

    /** Test constructor: an explicit log root. */
    public AiRolloutService(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    /**
     * Begins recording one turn. Returns a no-op-recording null when the conversation id
     * is absent (legacy flow turns) — callers null-check and skip.
     */
    public Recorder start(Long conversationId, String provider, String model) {
        if (conversationId == null) return null;
        Recorder recorder = new Recorder(fileFor(conversationId), conversationId);
        recorder.append(new RolloutEvent.TurnStarted(provider, model));
        return recorder;
    }

    /** Parsed events of one conversation's log, oldest first (empty when none exists). */
    public List<Map<String, Object>> events(Long conversationId) {
        return readEvents(fileFor(conversationId), Long.MAX_VALUE);
    }

    /** The typed view of {@link #events} — same lines, sealed records. */
    public List<RolloutEvent> typedEvents(Long conversationId) {
        List<RolloutEvent> typed = new ArrayList<>();
        for (Map<String, Object> raw : events(conversationId)) {
            typed.add(RolloutEvent.fromJson(raw));
        }
        return typed;
    }

    /**
     * Rebuilds the FengYu message list from recorded {@code message} events with
     * {@code seq <= upToSeq} — the resume view, and the prefix a fork starts from.
     */
    public List<AiChatMessage> rebuild(Long conversationId, long upToSeq) {
        List<AiChatMessage> history = new ArrayList<>();
        for (RolloutEvent event : typedEventsOf(fileFor(conversationId), upToSeq)) {
            if (event instanceof RolloutEvent.MessageAppended appended) {
                history.add(appended.message());
            }
        }
        return history;
    }

    /**
     * Copies the source log's events up to {@code upToSeq} into a fresh fork log under a
     * {@code fork} provenance header. The fork file is independent from here on. False
     * when the source log does not exist.
     */
    public boolean writeFork(Long sourceConversationId, long upToSeq, Long forkConversationId) {
        Path source = fileFor(sourceConversationId);
        if (!Files.isRegularFile(source)) return false;
        List<String> copied = new ArrayList<>();
        copied.add(lineOf(new RolloutEvent.Forked(sourceConversationId, upToSeq)));
        try {
            for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    Map<String, Object> event = JSON.readValue(line,
                            new TypeReference<Map<String, Object>>() {});
                    if (((Number) event.getOrDefault("seq", Long.MAX_VALUE)).longValue() <= upToSeq) {
                        copied.add(line);
                    }
                } catch (JsonProcessingException unparsable) {
                    // A torn tail line in the source is skipped, not copied.
                }
            }
            Path target = fileFor(forkConversationId);
            Files.createDirectories(target.getParent());
            Files.write(target, copied, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return true;
        } catch (IOException e) {
            log.debug("fork rollout copy failed: {}", e.toString());
            return false;
        }
    }

    // ── storage ──────────────────────────────────────────────────────────────────────────

    private Path fileFor(Long conversationId) {
        return root.resolve(String.valueOf(conversationId)).resolve("rollout.jsonl");
    }

    /** Reads and parses events with seq <= upToSeq; a torn/malformed line is skipped. */
    private static List<Map<String, Object>> readEvents(Path file, long upToSeq) {
        if (!Files.isRegularFile(file)) return List.of();
        List<Map<String, Object>> events = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (events.size() >= MAX_EVENTS) break;
                if (line.isBlank()) continue;
                try {
                    Map<String, Object> event = JSON.readValue(line,
                            new TypeReference<Map<String, Object>>() {});
                    if (((Number) event.getOrDefault("seq", Long.MAX_VALUE)).longValue() <= upToSeq) {
                        events.add(event);
                    }
                } catch (JsonProcessingException torn) {
                    // crash-torn tail or hand-edited line: skip, never fail the replay
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        return events;
    }

    private static List<RolloutEvent> typedEventsOf(Path file, long upToSeq) {
        List<RolloutEvent> typed = new ArrayList<>();
        for (Map<String, Object> raw : readEvents(file, upToSeq)) {
            typed.add(RolloutEvent.fromJson(raw));
        }
        return typed;
    }

    private static String lineOf(Map<String, Object> event) {
        try {
            return JSON.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static String lineOf(RolloutEvent event) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", event.type());
        line.putAll(event.toJson());
        return lineOf(line);
    }

    // ── recorder ─────────────────────────────────────────────────────────────────────────

    /**
     * Append-only handle for one turn. Every method is failure-swallowing: after the
     * first IO error the recorder marks itself broken and becomes a no-op so recording
     * can never take the chat turn down with it.
     */
    public static final class Recorder {
        private final Path file;
        private final Long conversationId;
        private long seq;
        private boolean broken;

        private Recorder(Path file, Long conversationId) {
            this.file = file;
            this.conversationId = conversationId;
            try {
                Files.createDirectories(file.getParent());
                // Continue the file's sequence across turns: seq is per-log monotonic.
                this.seq = Files.isRegularFile(file)
                        ? Files.readAllLines(file, StandardCharsets.UTF_8).size() : 0;
            } catch (IOException e) {
                this.broken = true;
            }
        }

        /** The single write path: envelope + payload + line. */
        private void append(RolloutEvent event) {
            if (broken) return;
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("type", event.type());
            line.putAll(event.toJson());
            line.put("seq", ++seq);
            line.put("ts", Instant.now().toString());
            line.put("conversationId", conversationId);
            try {
                Files.writeString(file, lineOf(line) + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                broken = true;
                log.debug("rollout recording disabled after write failure: {}", e.toString());
            }
        }

        public void message(AiChatMessage message) {
            append(new RolloutEvent.MessageAppended(message));
        }

        /** The RAW wire result (before any context limiter), capped. */
        public void toolResult(String callId, String name, String output, boolean success) {
            append(new RolloutEvent.ToolResultRecorded(callId, name, cap(output), success));
        }

        /** Full form: carries the sandbox audit object and/or the code-mode cell marker. */
        public void toolResult(String callId, String name, String output, boolean success,
                Map<String, Object> sandbox, String nestedIn) {
            append(new RolloutEvent.ToolResultRecorded(callId, name, cap(output), success,
                    sandbox, nestedIn));
        }

        public void compaction(String phase, long tokensBefore, long tokensAfter,
                long afterPrefixBefore, long afterPrefixAfter,
                boolean microcompacted, boolean degraded) {
            append(new RolloutEvent.CompactionApplied(phase, tokensBefore, tokensAfter,
                    afterPrefixBefore, afterPrefixAfter, microcompacted, degraded));
        }

        public void end(String reason, int completionTokens) {
            append(new RolloutEvent.TurnEnded(reason, completionTokens));
        }

        private static String cap(String output) {
            return output != null && output.length() > TOOL_OUTPUT_CAP
                    ? output.substring(0, TOOL_OUTPUT_CAP) + "…[capped]" : output;
        }
    }
}
