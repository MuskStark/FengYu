package fan.summer.fengyu.sdk;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
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
    private final BufferedReader reader;
    private final PrintWriter writer;
    private volatile boolean open = true;

    public StdioTransport(InputStream input, OutputStream output) {
        this.reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.writer = new PrintWriter(output, true, StandardCharsets.UTF_8);
    }

    @Override
    public String readFrame() throws Exception {
        String line = reader.readLine();
        if (line == null) {
            open = false;
            return null;
        }
        return line;
    }

    @Override
    public void writeFrame(String json) {
        writer.println(json);
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
