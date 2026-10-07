package fan.summer.fengyu.ai;

/**
 * Batch-D1 error contract: every model-request failure maps to exactly one of
 * these codes, carried by {@link AiServiceException#getCode()} and surfaced as the
 * additive {@code errorCode} field on SSE error events. The human-readable message
 * stays display-only — retry decisions, user hints, and log filtering key off the
 * code (ZCode's ErrorCode/FailureReason/RetryReason triple, reduced to the
 * granularity this app consumes).
 *
 * <ul>
 *   <li>{@link #AUTH_MISSING} — no API key / backend not configured (fix: settings)</li>
 *   <li>{@link #RATE_LIMITED} — provider 429-class; retry with backoff is sensible</li>
 *   <li>{@link #CONTEXT_OVERFLOW} — request exceeded the model's context window
 *       (fix: compact / shorten history)</li>
 *   <li>{@link #PROVIDER_4XX} — other provider-side rejection (bad model id, bad
 *       request shape); retrying verbatim will not help</li>
 *   <li>{@link #NETWORK} — connect/timeout/transport failures; transient</li>
 *   <li>{@link #ABORTED} — user cancellation / interrupt</li>
 *   <li>{@link #UNKNOWN} — everything else</li>
 * </ul>
 */
public enum AiErrorCode {
    AUTH_MISSING,
    RATE_LIMITED,
    CONTEXT_OVERFLOW,
    PROVIDER_4XX,
    NETWORK,
    ABORTED,
    UNKNOWN;

    /**
     * Classifies one failure into the contract. Recognition is cause-chain-deep:
     * official-SDK exception types carry the status shape, Spring AI's
     * {@code TransientAiException} marks retryable provider errors, and the
     * "cancelled" message is the loop's cancellation sentinel.
     */
    public static AiErrorCode of(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof AiServiceException asec && asec.getCode() != null) return asec.getCode();
            AiErrorCode byName = classifyByTypeName(t.getClass().getName(),
                    String.valueOf(t.getMessage()));
            if (byName != null) return byName;
        }
        return UNKNOWN;
    }

    /**
     * Name-based recognition of the official-SDK/Spring exception shapes. Package-
     * private seam so unit tests can drive the exact class names this matcher
     * expects; the companion test pins that the REAL SDK classes on the classpath
     * actually end with these suffixes.
     */
    static AiErrorCode classifyByTypeName(String name, String message) {
        if ("cancelled".equals(message)) return ABORTED;
        if (name.endsWith("RateLimitException") || name.endsWith("RateLimitedException")) return RATE_LIMITED;
        if (name.endsWith("BadRequestException")) {
            String msg = message.toLowerCase(java.util.Locale.ROOT);
            return msg.contains("context") || msg.contains("token limit")
                    || msg.contains("maximum context") ? CONTEXT_OVERFLOW : PROVIDER_4XX;
        }
        if (name.endsWith("UnauthorizedException") || name.endsWith("AuthenticationException")) return AUTH_MISSING;
        if (name.endsWith("NotFoundException")) return PROVIDER_4XX;
        if (name.endsWith("TimeoutException") || name.endsWith("ConnectException")
                || name.endsWith("RetryableException")
                || "org.springframework.web.client.ResourceAccessException".equals(name)) return NETWORK;
        if ("org.springframework.ai.retry.TransientAiException".equals(name)) return NETWORK;
        return null;
    }
}
