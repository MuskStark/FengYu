package fan.summer.fengyu.sdk;

/**
 * Exception a worker handler throws to produce a structured JSON-RPC error response. Carries
 * either a {@link RpcError.Code semantic code} (preferred for handler-level business errors) or
 * a raw numeric JSON-RPC code (for protocol-level errors raised by the dispatch loop itself,
 * e.g. {@code -32601} method-not-found).
 *
 * <p>The worker never puts stack traces or raw exception messages that may embed caller secrets
 * into the response. A handler that throws a plain (non-{@code RpcException}) {@link Throwable}
 * is mapped to {@link RpcError.Code#INTERNAL}: the caller receives a generic message plus the
 * stable {@code data.code} label, while the full causal chain and stack frames (but NOT the raw
 * message) are written to the worker's stderr for operator diagnostics. Only a handler-authored
 * {@code RpcException} carries its (controlled) message into the response.
 *
 * @since 1.4.0
 */
public class RpcException extends RuntimeException {
    private final RpcError.Code code;       // semantic label; null for raw protocol errors
    private final int jsonRpcCode;
    private final Object requestId;

    /** Semantic handler error — the preferred constructor for plugin code. */
    public RpcException(RpcError.Code code, String message) {
        super(message);
        this.code = code;
        this.jsonRpcCode = code.jsonRpcCode();
        this.requestId = null;
    }

    /** Raw JSON-RPC numeric code (protocol-level errors raised by the dispatch loop). */
    public RpcException(int jsonRpcCode, String message) {
        this(jsonRpcCode, message, null);
    }

    /** Raw JSON-RPC numeric code carrying the originating request id (for invalid-request paths). */
    public RpcException(int jsonRpcCode, String message, Object requestId) {
        super(message);
        this.code = null;
        this.jsonRpcCode = jsonRpcCode;
        this.requestId = requestId;
    }

    /** The semantic code, or {@code null} for raw protocol errors. */
    public RpcError.Code semanticCode() {
        return code;
    }

    /** The numeric code emitted in the {@code error.code} field. */
    public int code() {
        return jsonRpcCode;
    }

    /** The request id this error is bound to, or {@code null}. */
    public Object requestId() {
        return requestId;
    }
}
