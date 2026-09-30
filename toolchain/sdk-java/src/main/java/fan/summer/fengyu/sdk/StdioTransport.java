package fan.summer.fengyu.sdk;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * An {@link RpcTransport} backed by an {@link InputStream} / {@link OutputStream} pair, reading and
 * writing newline-delimited UTF-8 frames.
 *
 * <p>This is the production transport: {@code JsonRpcWorker.run()} wires {@code System.in} /
 * {@code System.out} through this class. It is also the test surface for in-memory streams
 * ({@link java.io.ByteArrayInputStream} / {@link java.io.ByteArrayOutputStream}).
 *
 * <p>The transport does <strong>not</strong> own its streams; {@link #close()} is a no-op so closing
 * the transport never closes {@code System.in}/{@code System.out} or a stream the caller still owns.
 */
public final class StdioTransport implements RpcTransport {
    /**
     * Hard cap on a single inbound frame (P1-6). The host caps stdout at 16 MiB; the worker side
     * caps inbound requests at the same value so a peer cannot drive the worker out of memory with
     * an oversized single line before the host's own guard fires.
     */
    static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;

    private final InputStream input;
    private final PrintWriter writer;
    private final int maxFrameBytes;
    private volatile boolean open = true;

    public StdioTransport(InputStream input, OutputStream output) {
        this(input, output, MAX_FRAME_BYTES);
    }

    /** Package-private small-limit seam for exact boundary tests. */
    StdioTransport(InputStream input, OutputStream output, int maxFrameBytes) {
        if (maxFrameBytes < 1) throw new IllegalArgumentException("maxFrameBytes must be positive");
        this.input = new BufferedInputStream(input);
        this.writer = new PrintWriter(output, true, StandardCharsets.UTF_8);
        this.maxFrameBytes = maxFrameBytes;
    }

    @Override
    public String readFrame() throws Exception {
        String line = readBoundedLine(input, maxFrameBytes);
        if (line == null) {
            open = false;
            return null;
        }
        return line;
    }

    /**
     * Read one line, throwing {@link java.io.IOException} if it exceeds {@code maxBytes} of UTF-8
     * before a newline (P1-6). Bounds memory against a peer that streams an enormous single line.
     */
    static String readBoundedLine(InputStream input, int maxBytes) throws java.io.IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') return decodeLine(line);
            if (line.size() >= maxBytes) {
                throw new java.io.IOException("inbound frame exceeded " + maxBytes
                    + " byte limit without a newline; closing transport to bound memory");
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
    public void writeFrame(String json) throws Exception {
        // The newline-delimited protocol assumes one frame == one line. A handler returning a
        // multiline string (file contents, a stack trace, a mail body) can serialize to JSON
        // containing escaped \n / \r — and once println emits the frame, those become real
        // newlines on stdout, splitting one response into multiple lines. The host's readLine()
        // then sees fragments that fail to parse as JSON-RPC, silently dropping the response and
        // hanging the caller until its timeout. The RpcTransport contract forbids embedded
        // newlines; sanitize here as the last line of defense rather than trusting every handler.
        if (json.indexOf('\n') >= 0 || json.indexOf('\r') >= 0) {
            json = json.replace('\r', ' ').replace('\n', ' ');
        }
        try {
            ensureFrameWithinLimit(json, maxFrameBytes, "outbound frame");
        } catch (java.io.IOException tooLarge) {
            open = false;
            throw tooLarge;
        }
        writer.println(json);
        // PrintWriter swallows IOExceptions into an internal error flag; a closed/broken stdout
        // pipe (host crashed mid-call) would otherwise look like a successful write and the caller
        // would hang until timeout. checkError() surfaces the failure so serve() can react.
        if (writer.checkError()) {
            open = false;
            throw new java.io.IOException("stdio write failed (stdout pipe closed or I/O error)");
        }
    }

    static void ensureFrameWithinLimit(String frame, int maxBytes, String label) throws java.io.IOException {
        int frameBytes = frame.getBytes(StandardCharsets.UTF_8).length;
        if (frameBytes > maxBytes) {
            throw new java.io.IOException(label + " exceeded " + maxBytes
                + " byte limit (was " + frameBytes + ")");
        }
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    /** Mark the transport as closed without touching the underlying streams. */
    @Override
    public void close() {
        open = false;
    }
}
