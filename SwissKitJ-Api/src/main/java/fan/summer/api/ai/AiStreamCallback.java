package fan.summer.api.ai;

/**
 * Callback for receiving streamed AI response tokens.
 */
public interface AiStreamCallback {

    /**
     * Called for each generated text fragment during streaming inference.
     *
     * @param fragment the decoded text piece (may be a partial word or punctuation)
     */
    void onToken(String fragment);

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
}
