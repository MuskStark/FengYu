package fan.summer.zhiflow.api.ai;

/**
 * Exception thrown by {@link ChatBackend} operations when model loading, inference,
 * or tool execution fails.
 *
 * @see ChatBackend
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
