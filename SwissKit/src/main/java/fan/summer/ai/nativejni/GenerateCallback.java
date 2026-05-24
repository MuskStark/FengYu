package fan.summer.ai.nativejni;

/**
 * Callback for receiving streamed tokens from native llama.cpp inference.
 */
public interface GenerateCallback {

    /**
     * Called for each generated token.
     *
     * @param tokenText the decoded text for this token
     * @return {@code false} to interrupt generation; {@code true} to continue
     */
    boolean onToken(String tokenText);

    /**
     * Called when generation completes normally.
     *
     * @param fullText the complete generated text
     */
    default void onDone(String fullText) {
        org.slf4j.LoggerFactory.getLogger(GenerateCallback.class)
            .debug("Generation done: length={}", fullText != null ? fullText.length() : 0);
    }

    /**
     * Called when an error occurs during generation.
     *
     * @param message error description
     */
    default void onError(String message) {
        org.slf4j.LoggerFactory.getLogger(GenerateCallback.class)
            .error("Generation error: {}", message);
    }
}
