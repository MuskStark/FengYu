package fan.summer.fengyu.api.ai;

/**
 * Callback for receiving streamed AI response tokens and tool call events.
 */
public interface AiStreamCallback {

    /**
     * Called for each generated text fragment during streaming inference.
     */
    void onToken(String fragment);

    /**
     * Called when the model emits a completed reasoning/thinking block (e.g. Qwen3's
     * {@code <think>…</think>}). The fragment is the full text of one closed thinking
     * block — callers render it as a unit. The default implementation discards it.
     *
     * <p>Only the local Qwen3 native backend invokes this today; cloud and Java-fallback
     * paths never call it (thinking is simply not surfaced there).</p>
     *
     * @param fragment the complete text of one thinking block (never {@code null})
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
     * Called when a tool execution completes, before the result is fed back to the model.
     *
     * @param toolCallId the ID of the tool call
     * @param result the execution result
     */
    default void onToolResult(String toolCallId, AiToolResult result) {}
}
