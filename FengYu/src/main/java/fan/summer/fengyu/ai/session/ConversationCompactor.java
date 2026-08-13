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
 */
public final class ConversationCompactor {

    public static final double TRIGGER_RATIO = 0.60d;
    public static final int DEFAULT_RECENT_ROUNDS = 8;
    public static final String SUMMARY_PREFIX = "[FengYu conversation summary]\n";
    public static final String SUMMARY_INSTRUCTIONS = """
            Summarize the supplied earlier conversation for use as context in later turns.
            Preserve user goals, decisions, constraints, names, paths, commands, errors, tool
            findings, and unresolved work. Do not answer a request, invent facts, or include
            conversational filler. Produce concise plain text.
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

        List<AiChatMessage> oldConversation = source.subList(0, split).stream()
                .filter(message -> message.role() != AiChatMessage.Role.SYSTEM)
                .toList();
        if (oldConversation.isEmpty()) return new Result(source, false, before, before);

        String summary;
        try {
            summary = summarizer.summarize(renderTranscript(oldConversation));
        } catch (Exception ignored) {
            return new Result(source, false, before, before);
        }
        if (summary == null || summary.isBlank()) {
            return new Result(source, false, before, before);
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
            for (AiChatMessage message : history) {
                tokens += 6; // role/framing overhead
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
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, tokens);
    }

    public static int estimateTextTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        return Math.max(1, (bytes + 3) / 4);
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

    private static String renderTranscript(List<AiChatMessage> history) {
        StringBuilder out = new StringBuilder();
        for (AiChatMessage message : history) {
            out.append(message.role().name());
            if (message.role() == AiChatMessage.Role.TOOL && message.toolName() != null) {
                out.append('(').append(message.toolName()).append(')');
            }
            out.append(":\n").append(message.content()).append("\n\n");
        }
        return out.toString();
    }
}
