package fan.summer.fengyu.devkit;

import fan.summer.fengyu.sdk.RpcTransport;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * A {@link RpcTransport} backed by a {@link Socket}, speaking the same newline-delimited UTF-8
 * framing as {@link fan.summer.fengyu.sdk.StdioTransport} (production stdio).
 *
 * <p>Used by {@link PluginDevServer}: one transport wraps each accepted loopback connection, and
 * the same {@code JsonRpcWorker.serve(transport)} loop that powers the production worker drives the
 * in-IDE debug session — so handler breakpoints fire without JDWP remote attach.
 *
 * <p>Each transport owns its socket: {@link #close()} closes the socket, which unblocks a pending
 * {@link #readFrame()} so the worker's {@code serve()} loop can exit cleanly when the dev server
 * shuts down.
 */
final class LineFramedSocketTransport implements RpcTransport {
    /** Inbound frame byte cap (P1-6): bounds dev-session memory against an oversized single line. */
    private static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;

    private final Socket socket;
    private final InputStream input;
    private final PrintWriter writer;
    private final Consumer<String> onDiag;

    LineFramedSocketTransport(Socket socket, Consumer<String> onDiag) throws IOException {
        this.socket = socket;
        this.input = new BufferedInputStream(socket.getInputStream());
        this.writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        this.onDiag = onDiag;
    }

    @Override
    public String readFrame() throws IOException {
        String line = readBoundedLine(input, MAX_FRAME_BYTES);
        // null on clean EOF; an empty line is a malformed frame and is surfaced to the worker, which
        // replies with -32700.
        return line;
    }

    /** Read one line, throwing if it exceeds {@code maxBytes} of UTF-8 before a newline (P1-6). */
    static String readBoundedLine(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') return decodeLine(line);
            if (line.size() >= maxBytes) {
                throw new IOException("inbound dev frame exceeded " + maxBytes
                    + " byte limit without a newline; closing to bound memory");
            }
            line.write(value);
        }
        return line.size() == 0 ? null : decodeLine(line);
    }

    private static String decodeLine(ByteArrayOutputStream line) {
        byte[] bytes = line.toByteArray();
        int length = bytes.length;
        if (length > 0 && bytes[length - 1] == '\r') length--;
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    @Override
    public void writeFrame(String json) throws IOException {
        // Mirror StdioTransport: a handler result (file contents, stack trace) can serialize to
        // JSON containing real newlines, which would split one frame into multiple lines and
        // corrupt the line-framed protocol the host reads. Sanitize before writing.
        if (json.indexOf('\n') >= 0 || json.indexOf('\r') >= 0) {
            json = json.replace('\r', ' ').replace('\n', ' ');
        }
        try {
            ensureFrameWithinLimit(json, MAX_FRAME_BYTES, "outbound dev frame");
        } catch (IOException tooLarge) {
            close();
            throw tooLarge;
        }
        writer.println(json);
        if (writer.checkError()) {
            onDiag.accept("dev transport: write failed (peer closed?)");
            close();
            throw new IOException("dev transport write failed (peer closed or I/O error)");
        }
    }

    static void ensureFrameWithinLimit(String frame, int maxBytes, String label) throws IOException {
        int frameBytes = frame.getBytes(StandardCharsets.UTF_8).length;
        if (frameBytes > maxBytes) {
            throw new IOException(label + " exceeded " + maxBytes
                + " byte limit (was " + frameBytes + ")");
        }
    }

    @Override
    public boolean isOpen() {
        return !socket.isClosed();
    }

    @Override
    public void close() throws IOException {
        try {
            socket.close();
        } catch (IOException e) {
            onDiag.accept("dev transport: close error: " + e.getMessage());
            throw e;
        }
    }
}
