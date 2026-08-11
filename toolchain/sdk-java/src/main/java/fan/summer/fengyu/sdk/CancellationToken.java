package fan.summer.fengyu.sdk;

/**
 * Per-call cancellation flag. Created by the worker for each JSON-RPC request and surfaced to
 * typed handlers via {@link RpcContext#cancellation()}. When the host sends a
 * {@code $/cancelRequest} notification (or the call times out / the UI aborts), the worker marks
 * the token and interrupts the handler thread; long-running handlers cooperative-check it via
 * {@link #throwIfCancelled()} so they can terminate promptly and return a {@code CANCELLED} error
 * instead of being killed as a crash.
 *
 * <p>This is transport-level cancellation of the <em>current</em> RPC. Domain job cancellation
 * (e.g. cancelling a background job started earlier by id) is a separate, plugin-level concern.
 *
 * @since 1.4.0
 */
public final class CancellationToken {
    private volatile boolean cancelled;

    /** {@code true} once the host has requested cancellation for this call. */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Throw {@link RpcException} with {@link RpcError.Code#CANCELLED} if the call has been
     * cancelled. Handlers should call this at cooperative checkpoints (loop iterations, before
     * slow I/O) so a cancel returns a clean {@code CANCELLED} response instead of running to
     * completion or being hard-interrupted.
     */
    public void throwIfCancelled() {
        if (cancelled) {
            throw new RpcException(RpcError.Code.CANCELLED, "request cancelled");
        }
    }

    /** Internal: mark the token cancelled. Called only by the worker dispatch loop. */
    void cancel() {
        cancelled = true;
    }
}
