package fan.summer.fengyu.sdk;

/**
 * Stable, semantic error model for FengYu worker RPC. Each {@link Code} maps to a numeric
 * JSON-RPC error code (so the wire envelope stays JSON-RPC 2.0 compliant) AND is carried as a
 * string label in {@code error.data.code} so the host and UI can branch on a stable identifier
 * instead of magic numbers.
 *
 * <p>Worker handlers throw {@link RpcException} with one of these codes; transport/protocol-level
 * errors (parse error, invalid request, unknown method) keep their standard JSON-RPC numeric
 * codes and are not forced into this enum.
 *
 * @since 1.4.0
 */
public final class RpcError {
    private RpcError() {}

    /**
     * Semantic error codes a worker handler may report. The numeric values live in the
     * JSON-RPC server-error reserved range ({@code -32000..-32099}) except where a standard
     * JSON-RPC code fits ({@code -32602} invalid params, {@code -32603} internal error).
     */
    public enum Code {
        /** A request parameter was missing, malformed, or failed validation. Maps to invalid params. */
        INVALID_ARGUMENT(-32602),
        /** The caller is not authorized to perform the operation. */
        PERMISSION_DENIED(-32001),
        /** A referenced resource (file, job, account, ...) does not exist. */
        NOT_FOUND(-32002),
        /** The request conflicts with current state (duplicate id, stale job, unique violation). */
        CONFLICT(-32003),
        /** The call was cancelled before completing (via {@code $/cancelRequest} or abort/timeout). */
        CANCELLED(-32800),
        /** An unexpected internal failure; details stay on stderr, not in the response. */
        INTERNAL(-32603);

        private final int jsonRpcCode;

        Code(int jsonRpcCode) {
            this.jsonRpcCode = jsonRpcCode;
        }

        /** The JSON-RPC numeric code to emit in the {@code error.code} field. */
        public int jsonRpcCode() {
            return jsonRpcCode;
        }
    }
}
