package fan.summer.fengyu.ai;

/**
 * Exception thrown by {@link ChatBackend} operations when model loading, inference,
 * or tool execution fails.
 *
 * @see ChatBackend
 */
public class AiServiceException extends Exception {

    /** The D1 error-contract code; derived from this exception or the cause chain. */
    private final AiErrorCode code;

    /**
     * Creates an exception with a message only.
     *
     * @param message a description of the error
     */
    public AiServiceException(String message) {
        this(message, null, null);
    }

    /**
     * Creates an exception with a message and underlying cause.
     *
     * @param message a description of the error
     * @param cause   the underlying cause
     */
    public AiServiceException(String message, Throwable cause) {
        this(message, cause, null);
    }

    /** Creates an exception with an explicit contract code (AUTH_MISSING, ABORTED, ...). */
    public AiServiceException(String message, Throwable cause, AiErrorCode code) {
        super(message, cause);
        this.code = code;
    }

    /**
     * The contract code for this failure: the explicit code when set, else the
     * classification of the cause chain, else UNKNOWN (a bare message-only
     * exception cannot be classified by shape).
     */
    public AiErrorCode getCode() {
        if (code != null) return code;
        if (getCause() != null) return AiErrorCode.of(getCause());
        return "cancelled".equals(getMessage()) ? AiErrorCode.ABORTED : AiErrorCode.UNKNOWN;
    }
}
