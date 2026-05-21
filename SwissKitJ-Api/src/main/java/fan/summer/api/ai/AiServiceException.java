package fan.summer.api.ai;

/**
 * Exception thrown by {@link AiService} operations.
 */
public class AiServiceException extends Exception {

    public AiServiceException(String message) {
        super(message);
    }

    public AiServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
