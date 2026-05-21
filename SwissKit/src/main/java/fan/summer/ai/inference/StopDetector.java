package fan.summer.ai.inference;

import java.util.List;

/**
 * Text-level stop-sequence detector applied to streaming LLM output.
 *
 * <p>The native engine and the pure-Java engine both fail to stop generation
 * reliably when the model emits a chat-template turn marker (e.g. {@code <|im_end|>},
 * {@code <|eot_id|>}) that the tokenizer never registered as the special EOS token.
 * Without an explicit text-level check the model keeps generating into a hallucinated
 * next user turn ({@code <|im_start|>user ...}) — i.e. self-conversation.
 *
 * <p>This detector scans the accumulated decoded text for any of the known turn
 * markers across ChatML / Llama-3 / Mistral / Gemma / Qwen templates. It returns
 * the earliest match position, which callers use to truncate the response and
 * abort the streaming loop.
 */
public final class StopDetector {

    private static final List<String> STOP_SEQUENCES = List.of(
        // ChatML / Qwen
        "<|im_end|>",
        "<|im_start|>",
        // Llama 3
        "<|eot_id|>",
        "<|start_header_id|>",
        "<|end_header_id|>",
        "<|end_of_text|>",
        // GPT-2 / GPT-Neo / some Qwen variants
        "<|endoftext|>",
        // Mistral / fallback
        "</s>",
        // Gemma
        "<end_of_turn>",
        "<start_of_turn>",
        // Generic role tags some fine-tunes emit
        "<|user|>",
        "<|assistant|>",
        "<|system|>"
    );

    private StopDetector() {}

    /**
     * @return earliest index of any known stop sequence in {@code text}, or {@code -1} if none.
     */
    public static int findStop(CharSequence text) {
        if (text == null || text.length() == 0) return -1;
        String s = text.toString();
        int best = -1;
        for (String stop : STOP_SEQUENCES) {
            int idx = s.indexOf(stop);
            if (idx >= 0 && (best < 0 || idx < best)) best = idx;
        }
        return best;
    }

    /**
     * @return {@code true} if {@code text} ends with a prefix that could become a stop sequence
     *         once more tokens are appended (e.g. {@code "<|im_"}). Callers may want to defer
     *         emitting the tail until the next token clarifies whether it really is a stop marker.
     */
    public static boolean endsWithPartialStop(CharSequence text) {
        if (text == null) return false;
        int len = text.length();
        if (len == 0) return false;
        String s = text.toString();
        for (String stop : STOP_SEQUENCES) {
            int max = Math.min(stop.length() - 1, len);
            for (int k = max; k >= 1; k--) {
                if (s.regionMatches(len - k, stop, 0, k)) return true;
            }
        }
        return false;
    }
}
