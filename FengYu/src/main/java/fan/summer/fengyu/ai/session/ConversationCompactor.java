package fan.summer.fengyu.ai.session;

import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiToolCall;
import fan.summer.fengyu.ai.AiMedia;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a bounded model-facing history by summarising the oldest complete conversation rounds.
 * The caller-owned transcript is never mutated, so the UI and durable conversation keep the full
 * text while the provider receives a compact context.
 *
 * <p>The algorithm follows the shape converged on by pi, grok-cli and deepseek-harness: cut only
 * at user-turn boundaries (never inside a tool call/result pair), summarize with a fixed
 * structured template, truncate tool results in the summarizer input, retry once with a shorter
 * slice when the summarizer fails, degrade to a hard truncation of the oldest complete rounds
 * when it fails twice, and — when even the default recent window cannot fit the
 * context — trade recent rounds for the limit instead of failing open with an oversized
 * history.</p>
 */
public final class ConversationCompactor {

    public static final double TRIGGER_RATIO = 0.60d;
    public static final int DEFAULT_RECENT_ROUNDS = 8;
    /** Recent rounds are only traded away below this when the kept tail itself overflows. */
    public static final int MIN_RECENT_ROUNDS = 2;
    /** Tool results are truncated in summarizer input so the summary call stays cheap and focused. */
    public static final int TOOL_RESULT_TRANSCRIPT_LIMIT = 2_000;
    public static final String SUMMARY_PREFIX = "[FengYu conversation summary]\n";
    public static final String SUMMARY_INSTRUCTIONS = """
            Summarize the supplied earlier conversation for use as context in later turns.
            Produce concise plain markdown with these sections, in order:
            ## Goal — the user's overarching objective.
            ## Constraints & Preferences — explicit rules, styles, or limits the user stated.
            ## Progress — what is done, in progress, and blocked.
            ## Key Decisions — choices made and why.
            ## Next Steps — unresolved work the next turns should continue.
            ## Critical Context — exact file paths, commands, identifiers, and error messages
            later turns must not lose.
            Omit a section only when genuinely empty. Do not answer a request, invent facts, or
            include conversational filler. Preserve identifiers, paths, and errors verbatim.
            """;

    private ConversationCompactor() {
    }

    @FunctionalInterface
    public interface Summarizer {
        String summarize(String transcript) throws Exception;
    }

    public record Result(List<AiChatMessage> history, boolean compacted, boolean microcompacted,
                         int estimatedTokensBefore, int estimatedTokensAfter) {
        public Result {
            history = List.copyOf(history);
        }

        /** Legacy shape (no eviction phase happened). */
        public Result(List<AiChatMessage> history, boolean compacted,
                      int estimatedTokensBefore, int estimatedTokensAfter) {
            this(history, compacted, false, estimatedTokensBefore, estimatedTokensAfter);
        }
    }

    /** Tool-result contents longer than this become eviction candidates. */
    static final int MICROCOMPACT_MIN_TOOL_CHARS = 400;
    /** User rounds whose tool results stay verbatim (the recent working set). */
    static final int MICROCOMPACT_KEEP_ROUNDS = 4;
    /** The placeholder an evicted tool result shrinks to. */
    static final String EVICTED_PLACEHOLDER =
            "[This tool result's content was evicted to free context. "
                    + "Re-run the tool if you need its details again.]";

    /**
     * Compacts only when the estimated input reaches 60% of the configured context window.
     * A value of {@code 0} disables compaction. When the summarizer is unavailable (invalid
     * key, provider outage) the history is degraded to a hard truncation of the oldest
     * complete rounds — never the fail-open oversized history the provider would reject.
     */
    public static Result compact(List<AiChatMessage> history, int contextWindowTokens,
                                 Summarizer summarizer) {
        return compact(history, contextWindowTokens, 0, summarizer);
    }

    /** Includes stable system/tool prompt overhead in the threshold and reported estimates. */
    public static Result compact(List<AiChatMessage> history, int contextWindowTokens,
                                 int promptOverheadTokens, Summarizer summarizer) {
        List<AiChatMessage> source = history == null ? List.of() : List.copyOf(history);
        int overhead = Math.max(0, promptOverheadTokens);
        int before = (int) Math.min(Integer.MAX_VALUE,
                (long) estimateTokens(source) + overhead);
        if (contextWindowTokens <= 0
                || before < Math.ceil(contextWindowTokens * TRIGGER_RATIO)) {
            return new Result(source, false, before, before);
        }

        // Phase 1 — microcompact: shrink the CONTENTS of old tool results (the read/grep/
        // build outputs that dominate coding sessions) while keeping every message and the
        // assistant-call/tool-result pairing intact. Lossless for everything except the
        // evicted outputs themselves, which the model can re-run when needed.
        List<AiChatMessage> evicted = microcompact(source);
        boolean microcompacted = evicted != source;
        int afterEviction = (int) Math.min(Integer.MAX_VALUE,
                (long) estimateTokens(evicted) + overhead);
        source = evicted;
        if (afterEviction < Math.ceil(contextWindowTokens * TRIGGER_RATIO)) {
            return new Result(source, true, true, before, afterEviction);
        }

        int split = recentRoundsStart(source, DEFAULT_RECENT_ROUNDS);
        if (split <= 0) {
            // Nothing older than the keep window to summarize — but the eviction phase DID
            // change what the model sees, so report it honestly instead of "not compacted".
            int afterNoCut = (int) Math.min(Integer.MAX_VALUE,
                    (long) estimateTokens(source) + overhead);
            return new Result(source, microcompacted, microcompacted, before, afterNoCut);
        }

        String summary = summarizeWithRetry(source, split, summarizer);
        // Summarizer unavailable (key invalid / provider down): degrade to a HARD truncation
        // of the oldest complete rounds (system messages + the recent tail). Returning the
        // unchanged history instead would ship a guaranteed over-window payload the provider
        // rejects with a 400 — losing the whole turn.
        boolean degraded = summary == null;

        // Relaxation (grok-cli pattern): when the kept tail alone still overflows the window,
        // shrink the verbatim tail round-by-round — never below MIN_RECENT_ROUNDS — instead of
        // returning a "compacted" history the provider will reject anyway.
        while (estimateTokens(source.subList(split, source.size()))
                        + (degraded ? 0 : estimateTextTokens(SUMMARY_PREFIX + summary)) + overhead
                > contextWindowTokens
                && userRoundCount(source.subList(split, source.size())) > MIN_RECENT_ROUNDS) {
            int next = nextUserBoundary(source, split);
            if (next <= split) break;
            split = next;
        }

        List<AiChatMessage> compacted = new ArrayList<>();
        source.subList(0, split).stream()
                .filter(message -> message.role() == AiChatMessage.Role.SYSTEM)
                .forEach(compacted::add);
        if (!degraded) {
            // USER, not assistant: a thinking endpoint demands reasoning_content on every
        // replayed assistant message — a host-injected summary must not wear that role.
        compacted.add(AiChatMessage.user(SUMMARY_PREFIX + summary.trim()));
        }
        compacted.addAll(source.subList(split, source.size()));
        int after = (int) Math.min(Integer.MAX_VALUE,
                (long) estimateTokens(compacted) + overhead);
        return new Result(compacted, true, microcompacted, before, after);
    }

    /**
     * Replaces the content of TOOL messages older than the last {@code MICROCOMPACT_KEEP_ROUNDS}
     * user rounds (only the bulky ones — and any carrying media) with {@link #EVICTED_PLACEHOLDER},
     * keeping ids, tool names, ordering, and every non-tool message verbatim. Media-bearing
     * results lose their images here too: without this, a read image or screenshot is
     * re-attached to EVERY subsequent request (quadratic provider cost) — the transcript
     * keeps the original (this list is only what the model sees), and the model can re-run
     * the tool when it needs the picture again. Returns the input reference when nothing
     * qualified (no allocation). Package-private for tests.
     */
    static List<AiChatMessage> microcompact(List<AiChatMessage> history) {
        int keepFrom = recentRoundsStart(history, MICROCOMPACT_KEEP_ROUNDS);
        // Fewer user rounds than the keep window: the whole conversation IS the recent
        // working set — nothing is old enough to evict.
        if (keepFrom < 0) return history;
        List<AiChatMessage> out = null;
        for (int i = 0; i < history.size(); i++) {
            AiChatMessage message = history.get(i);
            boolean evictable = message.role() == AiChatMessage.Role.TOOL
                    && i < keepFrom
                    && !EVICTED_PLACEHOLDER.equals(message.content())
                    && ((message.content() != null
                            && message.content().length() > MICROCOMPACT_MIN_TOOL_CHARS)
                        || !message.media().isEmpty());
            if (!evictable) continue;
            if (out == null) out = new ArrayList<>(history);
            out.set(i, AiChatMessage.toolResult(message.toolCallId(), message.toolName(),
                    EVICTED_PLACEHOLDER));
        }
        return out == null ? history : out;
    }

    /** Conservative provider-neutral estimate: UTF-8 bytes catch CJK text better than chars/4. */
    public static int estimateTokens(List<AiChatMessage> history) {
        long tokens = 0;
        if (history != null) {
            for (AiChatMessage message : history) tokens += estimateMessageTokens(message);
        }
        return (int) Math.min(Integer.MAX_VALUE, tokens);
    }

    public static int estimateTextTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        return Math.max(1, (bytes + 3) / 4);
    }

    private static long estimateMessageTokens(AiChatMessage message) {
        long tokens = 6; // role/framing overhead
        tokens += estimateTextTokens(message.content());
        tokens += estimateTextTokens(message.reasoningContent());
        for (AiToolCall call : message.toolCalls()) {
            tokens += 8 + estimateTextTokens(call.name())
                    + estimateTextTokens(String.valueOf(call.arguments()));
        }
        tokens += estimateTextTokens(message.toolName());
        for (AiMedia media : message.media()) {
            // Providers tokenize images by dimensions/tiles rather than base64 length.
            // Use a conservative fixed estimate without inflating context by encoded bytes.
            tokens += 1_024;
        }
        return tokens;
    }

    private static int recentRoundsStart(List<AiChatMessage> history, int roundsToKeep) {
        int users = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).role() != AiChatMessage.Role.USER) continue;
            users++;
            if (users == roundsToKeep) return i;
        }
        return -1;
    }

    private static int nextUserBoundary(List<AiChatMessage> history, int after) {
        for (int i = after + 1; i < history.size(); i++) {
            if (history.get(i).role() == AiChatMessage.Role.USER) return i;
        }
        return -1;
    }

    private static int userRoundCount(List<AiChatMessage> tail) {
        int users = 0;
        for (AiChatMessage message : tail) {
            if (message.role() == AiChatMessage.Role.USER) users++;
        }
        return users;
    }

    /**
     * Summarizes everything before the split. On failure, retries exactly once with the more
     * recent half of that span — losing some oldest detail in the summary beats failing open
     * and shipping the full oversized history to the provider.
     */
    private static String summarizeWithRetry(List<AiChatMessage> source, int split,
                                             Summarizer summarizer) {
        List<AiChatMessage> oldConversation = source.subList(0, split).stream()
                .filter(message -> message.role() != AiChatMessage.Role.SYSTEM)
                .toList();
        if (oldConversation.isEmpty()) return null;
        String summary;
        try {
            summary = summarize(summarizer, oldConversation);
        } catch (Exception first) {
            int half = oldConversation.size() / 2;
            if (half <= 0) return null;
            try {
                summary = summarize(summarizer, oldConversation.subList(half, oldConversation.size()));
            } catch (Exception second) {
                first.addSuppressed(second);
                return null;
            }
        }
        return summary == null || summary.isBlank() ? null : summary;
    }

    private static String summarize(Summarizer summarizer, List<AiChatMessage> conversation)
            throws Exception {
        String summary = summarizer.summarize(renderTranscript(conversation));
        return summary == null || summary.isBlank() ? null : summary.trim();
    }

    private static String renderTranscript(List<AiChatMessage> history) {
        StringBuilder out = new StringBuilder();
        for (AiChatMessage message : history) {
            out.append(message.role().name());
            if (message.role() == AiChatMessage.Role.TOOL && message.toolName() != null) {
                out.append('(').append(message.toolName()).append(')');
            }
            out.append(":\n");
            if (message.role() == AiChatMessage.Role.TOOL) {
                out.append(truncateToolResult(message.content()));
            } else {
                out.append(message.content());
            }
            out.append("\n\n");
        }
        return out.toString();
    }

    private static String truncateToolResult(String content) {
        if (content == null || content.length() <= TOOL_RESULT_TRANSCRIPT_LIMIT) return content;
        return content.substring(0, TOOL_RESULT_TRANSCRIPT_LIMIT) + "\n…[truncated]";
    }

    // ── mid-turn compaction (between tool rounds, on the live Spring conversation) ───────

    /**
     * Deliberately LATER than the turn-start {@link #TRIGGER_RATIO}: compacting mid-turn
     * rewrites the working conversation and invalidates the provider's prefix cache from
     * the first changed message, so between rounds we spend the window down to 85% before
     * paying that price (terminal coding-agent practice: cache-preserving token scopes).
     */
    public static final double MID_TURN_TRIGGER_RATIO = 0.85d;
    /** Working rounds kept verbatim by a mid-turn compaction — relief, not a full recap. */
    public static final int MID_TURN_KEEP_ROUNDS = 2;
    public static final String MID_TURN_SUMMARY_PREFIX = "[FengYu mid-turn summary]\n";

    /**
     * The two token scopes a turn actually pays: the whole prompt, and the part AFTER the
     * cached prefix (what the provider must freshly process). A long conversation whose
     * growth is all cache hits stays far under its window in the after-prefix scope — the
     * accounting that decides whether a mid-turn compaction (which breaks the cache) is
     * worth it.
     */
    public record TokenScopes(int totalTokens, int afterPrefixTokens) {}

    public record MidTurnResult(List<Message> conversation, boolean compacted,
            boolean degraded, TokenScopes before, TokenScopes after) {
        public MidTurnResult {
            conversation = List.copyOf(conversation);
        }
    }

    /** Dual-scope accounting for the live conversation against a cached-prefix baseline. */
    public static TokenScopes tokenScopes(List<Message> conversation, int cachedPrefixTokens) {
        int total = estimateSpringTokens(conversation);
        int baseline = Math.max(0, cachedPrefixTokens);
        return new TokenScopes(total, Math.max(0, total - baseline));
    }

    /** Reserve kept between the clamped output budget and the window edge. */
    public static final int OUTPUT_CLAMP_RESERVE_TOKENS = 1_000;

    /**
     * The output budget one round may actually ask for: the model's cap, clamped down by
     * whatever context headroom remains (the ZCode preflight cap — input plus output must
     * fit the window, or the provider rejects the request outright). A non-positive
     * headroom estimate keeps the baseline instead of sending an unusable zero: the local
     * estimate is not authoritative, and the provider's own rejection drives the
     * compaction recovery paths either way.
     */
    public static int clampMaxOutputTokens(int modelMaxOutputTokens, int contextWindowTokens,
            int estimatedInputTokens) {
        if (modelMaxOutputTokens <= 0 || contextWindowTokens <= 0 || estimatedInputTokens < 0) {
            return modelMaxOutputTokens;
        }
        int available = contextWindowTokens - estimatedInputTokens - OUTPUT_CLAMP_RESERVE_TOKENS;
        return available > 0 ? Math.min(modelMaxOutputTokens, available) : modelMaxOutputTokens;
    }

    /**
     * Compacts the live Spring conversation between tool rounds when it crosses
     * {@link #MID_TURN_TRIGGER_RATIO} of the window. The REUSABLE PREFIX — the system
     * message and the initial request, everything before the first tool round — is kept
     * verbatim (it is the provider-cache prefix and the turn's premise); the middle rounds
     * are summarized into one assistant message; the last {@link #MID_TURN_KEEP_ROUNDS}
     * tool rounds stay byte-identical, their assistant/tool-response pairing never split.
     * A failed summarizer degrades to dropping the middle entirely — a mid-turn overflow
     * must never fail open with a payload the provider would reject.
     */
    public static MidTurnResult compactMidTurn(List<Message> conversation,
            int contextWindowTokens, int cachedPrefixTokens, Summarizer summarizer) {
        List<Message> source = conversation == null ? List.of() : List.copyOf(conversation);
        int total = estimateSpringTokens(source);
        TokenScopes before = new TokenScopes(total,
                Math.max(0, total - Math.max(0, cachedPrefixTokens)));
        if (contextWindowTokens <= 0
                || total < Math.ceil(contextWindowTokens * MID_TURN_TRIGGER_RATIO)) {
            return new MidTurnResult(source, false, false, before, before);
        }
        int prefixEnd = firstToolRoundStart(source);
        if (prefixEnd <= 0) {
            // No tool rounds yet: this is the turn-start compactor's territory.
            return new MidTurnResult(source, false, false, before, before);
        }
        int tailStart = lastRoundsStart(source, MID_TURN_KEEP_ROUNDS);
        if (tailStart <= prefixEnd) {
            // The keep window already covers every working round — nothing to summarize.
            return new MidTurnResult(source, false, false, before, before);
        }

        String summary = summarizeMiddle(source, prefixEnd, tailStart, summarizer);
        boolean degraded = summary == null;
        List<Message> compacted = new ArrayList<>(source.subList(0, prefixEnd));
        if (!degraded) {
            // UserMessage, never AssistantMessage: a thinking endpoint rejects a
            // replayed assistant summary without reasoning_content (the live DeepSeek
            // 400 fired exactly here — compaction, then the next request).
            compacted.add(new org.springframework.ai.chat.messages.UserMessage(
                    MID_TURN_SUMMARY_PREFIX + summary.trim()));
        }
        compacted.addAll(source.subList(tailStart, source.size()));

        // After the cut the surviving cache prefix is the verbatim head; report the
        // after-scope against it so the numbers say what the next round actually pays.
        int headTokens = estimateSpringTokens(source.subList(0, prefixEnd));
        int afterTotal = estimateSpringTokens(compacted);
        TokenScopes after = new TokenScopes(afterTotal, Math.max(0, afterTotal - headTokens));
        return new MidTurnResult(compacted, true, degraded, before, after);
    }

    /** Index of the first assistant message carrying tool calls, or -1 when there is none. */
    private static int firstToolRoundStart(List<Message> conversation) {
        for (int i = 0; i < conversation.size(); i++) {
            if (hasToolCalls(conversation.get(i))) return i;
        }
        return -1;
    }

    /** Index where the last {@code rounds} tool rounds begin; they stay verbatim. */
    private static int lastRoundsStart(List<Message> conversation, int rounds) {
        int seen = 0;
        for (int i = conversation.size() - 1; i >= 0; i--) {
            if (!hasToolCalls(conversation.get(i))) continue;
            seen++;
            if (seen == rounds) return i;
        }
        return seen == 0 ? -1 : 0;
    }

    private static boolean hasToolCalls(Message message) {
        return message instanceof AssistantMessage assistant && assistant.hasToolCalls();
    }

    /** Single-attempt middle summary; null on failure/blank (degrade, never throw). */
    private static String summarizeMiddle(List<Message> source, int from, int to,
            Summarizer summarizer) {
        StringBuilder transcript = new StringBuilder();
        for (int i = from; i < to; i++) {
            Message message = source.get(i);
            if (message instanceof ToolResponseMessage response) {
                for (ToolResponseMessage.ToolResponse tool : response.getResponses()) {
                    transcript.append("TOOL(").append(tool.name()).append("):\n")
                            .append(truncateToolResult(tool.responseData())).append("\n\n");
                }
                continue;
            }
            String role = message instanceof AssistantMessage ? "ASSISTANT"
                    : message.getMessageType().name();
            transcript.append(role).append(":\n")
                    .append(truncateToolResult(message.getText())).append("\n\n");
        }
        if (transcript.isEmpty()) return null;
        try {
            String summary = summarizer.summarize(transcript.toString());
            return summary == null || summary.isBlank() ? null : summary.trim();
        } catch (Exception unavailable) {
            return null;
        }
    }

    /** Provider-neutral estimate over the live Spring message list (same heuristic as above). */
    public static int estimateSpringTokens(List<Message> conversation) {
        long tokens = 0;
        if (conversation != null) {
            for (Message message : conversation) tokens += estimateSpringMessageTokens(message);
        }
        return (int) Math.min(Integer.MAX_VALUE, tokens);
    }

    private static long estimateSpringMessageTokens(Message message) {
        long tokens = 6; // role/framing overhead
        if (message instanceof ToolResponseMessage response) {
            for (ToolResponseMessage.ToolResponse tool : response.getResponses()) {
                tokens += 8 + estimateTextTokens(tool.name())
                        + estimateTextTokens(tool.responseData());
            }
            return tokens;
        }
        tokens += estimateTextTokens(message.getText());
        if (message instanceof AssistantMessage assistant) {
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                tokens += 8 + estimateTextTokens(call.name())
                        + estimateTextTokens(String.valueOf(call.arguments()));
            }
        }
        if (message instanceof UserMessage user) {
            tokens += 1_024L * user.getMedia().size();
        }
        return tokens;
    }
}
