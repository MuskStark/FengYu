package fan.summer.api.ai;

/**
 * Exception thrown by {@link AiService} operations when model loading, inference,
 * or tool execution fails.
 *
 * @see AiService
 */
public class AiServiceException extends Exception {

    /**
     * Creates an exception with a message only.
     *
     * @param message a description of the error
     */
    public AiServiceException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and underlying cause.
     *
     * @param message a description of the error
     * @param cause   the underlying cause
     */
    public AiServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
