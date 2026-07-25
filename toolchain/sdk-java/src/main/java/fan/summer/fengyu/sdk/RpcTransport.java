package fan.summer.fengyu.sdk;

/**
 * A frame-oriented transport for newline-delimited JSON-RPC 2.0 messages.
 *
 * <p>Decouples {@link JsonRpcWorker}'s dispatch loop from <em>how</em> frames are read and written.
 * Production workers speak JSON-RPC over their process stdin/stdout ({@link StdioTransport}); the
 * development kit speaks the same line-framed protocol over a loopback TCP socket
 * ({@code LineFramedSocketTransport} in {@code fengyu-plugin-devkit}). Both transports share the
 * same {@link JsonRpcWorker#serve(RpcTransport)} dispatch loop.
 *
 * <p>One frame = one UTF-8 JSON-RPC object terminated by a newline. {@link #readFrame()} returns
 * {@code null} at end-of-stream to signal a clean shutdown.
 */
public interface RpcTransport extends AutoCloseable {
    /**
     * Read the next frame (a single JSON object, without the trailing newline).
     *
     * @return the frame, or {@code null} when the peer has closed the stream cleanly
     * @throws Exception on a read failure mid-frame
     */
    String readFrame() throws Exception;

    /**
     * Write a single frame followed by a newline, flushing the underlying stream.
     *
     * @param json the JSON-RPC object to send (must not contain an embedded newline)
     * @throws Exception on a write failure
     */
    void writeFrame(String json) throws Exception;

    /**
     * @return whether the transport can still be read from / written to
     */
    boolean isOpen();

    /**
     * Release any underlying resources (sockets, streams). The default implementation does nothing;
     * transports whose resources are owned by the caller (e.g. stdin/stdout) leave them open.
     */
    @Override
    default void close() throws Exception {}
}
