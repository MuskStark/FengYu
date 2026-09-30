package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.AiStreamCallback;

/**
 * Forwards reasoning/thinking fragments from Spring AI stream chunks to
 * {@link AiStreamCallback#onThinking} as append-only deltas.
 *
 * <p>Spring AI 2.0 exposes reasoning under two fragment semantics depending on the
 * provider, and this class adapts both:</p>
 * <ul>
 *   <li><b>ACCUMULATED</b> — OpenAI-compatible models (GLM, DeepSeek): each chunk's
 *       {@code AssistantMessage} metadata {@code "reasoningContent"} carries the
 *       running concatenation, so only the suffix beyond what was already sent is
 *       forwarded. Shorter-or-equal values (defensive: a provider replaying the map)
 *       are skipped.</li>
 *   <li><b>DELTA</b> — Ollama: each chunk's metadata {@code "thinking"} is that
 *       chunk's own fragment, forwarded verbatim.</li>
 * </ul>
 *
 * <p>One forwarder per model stream (per tool-loop round): a fresh instance resets the
 * sent-length bookkeeping, which is exactly the per-round reset the backends need.</p>
 */
final class ReasoningForwarder {

    enum Mode { ACCUMULATED, DELTA }

    private final Mode mode;
    private int sentLength;
    private final StringBuilder total = new StringBuilder();

    private ReasoningForwarder(Mode mode) {
        this.mode = mode;
    }

    static ReasoningForwarder accumulated() {
        return new ReasoningForwarder(Mode.ACCUMULATED);
    }

    static ReasoningForwarder delta() {
        return new ReasoningForwarder(Mode.DELTA);
    }

    /** The full reasoning seen so far (the sum of every emitted delta). */
    String total() {
        return total.toString();
    }

    /**
     * Offers one metadata fragment; emits the delta (if any) to the callback.
     *
     * @param fragment the metadata value; non-String values are ignored (forward-compat
     *                 with providers that switch the field's shape)
     */
    void offer(Object fragment, AiStreamCallback callback) {
        if (!(fragment instanceof String text) || text.isEmpty()) return;
        String outgoing;
        if (mode == Mode.DELTA) {
            outgoing = text;
        } else {
            if (text.length() <= sentLength) return;
            outgoing = text.substring(sentLength);
            sentLength = text.length();
        }
        total.append(outgoing);
        callback.onThinking(outgoing);
    }
}
