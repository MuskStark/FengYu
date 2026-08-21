package fan.summer.fengyu.ai.session;

import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiToolCall;
import fan.summer.fengyu.ai.AiMedia;

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
 * slice when the summarizer fails, and — when even the default recent window cannot fit the
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

    public record Result(List<AiChatMessage> history, boolean compacted,
                         int estimatedTokensBefore, int estimatedTokensAfter) {
        public Result {
            history = List.copyOf(history);
        }
    }

    /**
     * Compacts only when the estimated input reaches 60% of the configured context window.
     * A value of {@code 0} disables compaction. Failures are non-fatal and leave history intact.
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

        int split = recentRoundsStart(source, DEFAULT_RECENT_ROUNDS);
        if (split <= 0) return new Result(source, false, before, before);

        String summary = summarizeWithRetry(source, split, summarizer);
        if (summary == null) return new Result(source, false, before, before);

        // Relaxation (grok-cli pattern): when the kept tail alone still overflows the window,
        // shrink the verbatim tail round-by-round — never below MIN_RECENT_ROUNDS — instead of
        // returning a "compacted" history the provider will reject anyway.
        while (estimateTokens(source.subList(split, source.size()))
                        + estimateTextTokens(SUMMARY_PREFIX + summary) + overhead
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
        compacted.add(AiChatMessage.assistant(SUMMARY_PREFIX + summary.trim()));
        compacted.addAll(source.subList(split, source.size()));
        int after = (int) Math.min(Integer.MAX_VALUE,
                (long) estimateTokens(compacted) + overhead);
        return new Result(compacted, true, before, after);
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
}
