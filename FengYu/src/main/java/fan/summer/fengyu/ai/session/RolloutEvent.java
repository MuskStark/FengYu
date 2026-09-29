package fan.summer.fengyu.ai.session;

import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiToolCall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The typed event model of the rollout log — one JSONL line is one event. The wire
 * format is exactly the historical one (a {@code type} discriminator plus per-type
 * fields), so pre-existing logs replay unchanged; this sealed hierarchy replaces the
 * schemaless {@code Map<String, Object>} events as the single vocabulary every writer
 * and reader shares, and is the extension point for upcoming specializations (e.g. a
 * sandbox {@code tool_result} payload, code-mode {@code nestedIn} markers).
 *
 * <p>Envelope fields ({@code seq}/{@code ts}/{@code conversationId}) are log-file
 * concerns applied by {@link AiRolloutService.Recorder} on append — events themselves
 * carry only their payload. Unknown {@code type} values (a newer log version read by
 * an older build) deserialize as {@link Unknown} and are skipped by replay, preserving
 * the historical forward-compatibility rule.</p>
 */
public sealed interface RolloutEvent permits
        RolloutEvent.TurnStarted,
        RolloutEvent.MessageAppended,
        RolloutEvent.ToolResultRecorded,
        RolloutEvent.CompactionApplied,
        RolloutEvent.TurnEnded,
        RolloutEvent.Forked,
        RolloutEvent.Unknown {

    /** The JSONL {@code type} discriminator — the historical string, verbatim. */
    String type();

    /** Payload fields (no {@code type}, no envelope) in stable field order. */
    Map<String, Object> toJson();

    /** Parses one logged line's map into its typed event; unknown types become {@link Unknown}. */
    static RolloutEvent fromJson(Map<String, Object> raw) {
        if (raw == null) return new Unknown(Map.of());
        String type = str(raw.get("type"));
        return switch (type == null ? "" : type) {
            case "turn_start" -> new TurnStarted(str(raw.get("provider")), str(raw.get("model")));
            case "message" -> {
                AiChatMessage parsed = messageOf(raw);
                yield parsed != null ? new MessageAppended(parsed) : new Unknown(raw);
            }
            case "tool_result" -> new ToolResultRecorded(str(raw.get("callId")), str(raw.get("name")),
                    str(raw.get("output")), Boolean.TRUE.equals(raw.get("success")));
            case "compaction" -> new CompactionApplied(str(raw.get("phase")),
                    num(raw.get("tokensBefore")), num(raw.get("tokensAfter")),
                    num(raw.get("afterPrefixBefore")), num(raw.get("afterPrefixAfter")),
                    Boolean.TRUE.equals(raw.get("microcompacted")),
                    Boolean.TRUE.equals(raw.get("degraded")));
            case "turn_end" -> new TurnEnded(str(raw.get("reason")), (int) num(raw.get("completionTokens")));
            case "fork" -> new Forked(raw.get("source") instanceof Number n ? n.longValue() : null,
                    num(raw.get("upToSeq")));
            default -> new Unknown(raw);
        };
    }

    // ── event types ──────────────────────────────────────────────────────────────────────

    /** One recorded turn's opening boundary: which provider/model served it. */
    record TurnStarted(String provider, String model) implements RolloutEvent {
        @Override public String type() { return "turn_start"; }
        @Override public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("provider", provider);
            json.put("model", model);
            return json;
        }
    }

    /** One message the model saw or produced — the only event type replay rebuilds from. */
    record MessageAppended(AiChatMessage message) implements RolloutEvent {
        @Override public String type() { return "message"; }
        @Override public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("role", message.role().name());
            json.put("content", message.content());
            if (message.reasoningContent() != null && !message.reasoningContent().isBlank()) {
                json.put("reasoning", message.reasoningContent());
            }
            if (!message.toolCalls().isEmpty()) {
                List<Map<String, Object>> calls = new ArrayList<>();
                for (AiToolCall call : message.toolCalls()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", call.id());
                    entry.put("name", call.name());
                    entry.put("arguments", call.arguments());
                    calls.add(entry);
                }
                json.put("toolCalls", calls);
            }
            if (message.role() == AiChatMessage.Role.TOOL) {
                json.put("toolCallId", message.toolCallId());
                json.put("toolName", message.toolName());
            }
            return json;
        }
    }

    /** The RAW wire result of one tool call (pre-context-limiter), capped by the recorder. */
    record ToolResultRecorded(String callId, String name, String output, boolean success)
            implements RolloutEvent {
        @Override public String type() { return "tool_result"; }
        @Override public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("callId", callId);
            json.put("name", name);
            json.put("success", success);
            json.put("output", output);
            return json;
        }
    }

    /** A compaction cut (turn-start or mid-turn), with cache-prefix scope accounting. */
    record CompactionApplied(String phase, long tokensBefore, long tokensAfter,
            long afterPrefixBefore, long afterPrefixAfter,
            boolean microcompacted, boolean degraded) implements RolloutEvent {
        @Override public String type() { return "compaction"; }
        @Override public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("phase", phase);
            json.put("tokensBefore", tokensBefore);
            json.put("tokensAfter", tokensAfter);
            json.put("afterPrefixBefore", afterPrefixBefore);
            json.put("afterPrefixAfter", afterPrefixAfter);
            json.put("microcompacted", microcompacted);
            json.put("degraded", degraded);
            return json;
        }
    }

    /** One recorded turn's closing boundary and how it ended. */
    record TurnEnded(String reason, int completionTokens) implements RolloutEvent {
        @Override public String type() { return "turn_end"; }
        @Override public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("reason", reason);
            json.put("completionTokens", completionTokens);
            return json;
        }
    }

    /** The provenance header written at the head of a fork's copied log. */
    record Forked(Long source, long upToSeq) implements RolloutEvent {
        @Override public String type() { return "fork"; }
        @Override public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("source", source);
            json.put("upToSeq", upToSeq);
            return json;
        }
    }

    /** A newer log version's event this build cannot interpret — preserved raw, skipped by replay. */
    record Unknown(Map<String, Object> raw) implements RolloutEvent {
        @Override public String type() { return str(raw.get("type")); }
        @Override public Map<String, Object> toJson() { return new LinkedHashMap<>(raw); }
    }

    // ── parsing helpers (shared with the historical format) ─────────────────────────────

    private static AiChatMessage messageOf(Map<String, Object> raw) {
        String role = str(raw.get("role"));
        String content = raw.get("content") == null ? "" : String.valueOf(raw.get("content"));
        return switch (role == null ? "" : role) {
            case "SYSTEM" -> AiChatMessage.system(content);
            case "USER" -> AiChatMessage.user(content);
            case "TOOL" -> AiChatMessage.toolResult(
                    str(raw.get("toolCallId")), str(raw.get("toolName")), content);
            case "ASSISTANT" -> {
                List<AiToolCall> calls = new ArrayList<>();
                if (raw.get("toolCalls") instanceof List<?> list) {
                    for (Object entry : list) {
                        if (!(entry instanceof Map<?, ?> call)) continue;
                        Map<String, Object> arguments = call.get("arguments") instanceof Map<?, ?> args
                                ? castArgs(args) : Map.of();
                        calls.add(AiToolCall.of(
                                str(call.get("id")), str(call.get("name")), arguments));
                    }
                }
                yield calls.isEmpty()
                        ? AiChatMessage.assistant(content)
                        : AiChatMessage.assistantWithTools(content, calls);
            }
            default -> null; // unknown role from a newer log version: skipped by replay
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castArgs(Map<?, ?> args) {
        return (Map<String, Object>) args;
    }

    private static long num(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
