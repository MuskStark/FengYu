package fan.summer.fengyu.ai;

import java.time.Instant;

/**
 * Callback for receiving streamed AI response tokens and tool call events.
 */
public interface AiStreamCallback {

    /**
     * Called for each generated text fragment during streaming inference.
     */
    void onToken(String fragment);

    /**
     * Called for each reasoning/thinking fragment as it streams in. Fragments are
     * <b>append-only deltas</b> (same semantics as {@link #onToken}): concatenating
     * every fragment in order reconstructs the full chain-of-thought, which never
     * interleaves with the answer text — the model reasons first, then answers.
     *
     * <p>Emitting backends (4.1.0): OpenAI-compatible cloud models surface
     * {@code reasoning_content} (accumulated upstream, forwarded as suffix deltas) and
     * local Ollama models with the {@code thinking} capability surface per-chunk
     * fragments. The Anthropic provider does not participate: Spring AI's streaming
     * path exposes only a thinking marker per chunk (the text lands on the final
     * response) and thinking is not requested there. The default implementation
     * discards fragments.</p>
     *
     * @param fragment one reasoning delta (never {@code null}, never empty)
     */
    default void onThinking(String fragment) {}

    /**
     * Called when generation is complete (either by EOS token or max tokens reached).
     *
     * @param fullResponse the complete response text
     * @param tokensGenerated number of tokens generated in this response
     * @param tokensPerSecond generation speed (tokens/second), 0 if not measurable
     */
    default void onComplete(String fullResponse, int tokensGenerated, double tokensPerSecond) {}

    /**
     * Called when an error occurs during generation.
     */
    default void onError(Throwable error) {}

    /**
     * Called when the model requests a tool invocation.
     * The engine will execute the tool and feed the result back to the model
     * before continuing generation.
     *
     * @param toolCall the tool call requested by the model
     */
    default void onToolCall(AiToolCall toolCall) {}

    /**
     * Called before a sensitive tool is executed. The chat remains paused until the user
     * approves or rejects the request identified by {@code approvalId}.
     */
    default void onToolApprovalRequired(String approvalId, AiToolCall toolCall, Instant expiresAt) {}

    /**
     * Called when a tool execution completes, before the result is fed back to the model.
     *
     * @param toolCallId the ID of the tool call
     * @param result the execution result
     */
    default void onToolResult(String toolCallId, AiToolResult result) {}

    /**
     * Called once at turn start (after any compaction decision) with the estimated
     * context-window usage, so the UI can show a context indicator. The default
     * implementation discards it.
     *
     * @since 4.1.0
     */
    default void onUsage(ContextUsage usage) {}

    /** Estimated context usage of one turn (token estimates, not provider-reported bills). */
    record ContextUsage(int contextTokens, int contextWindowTokens, boolean compacted,
                        boolean microcompacted) {}
}
