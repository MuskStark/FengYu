package fan.summer.fengyu.devkit;

import fan.summer.fengyu.sdk.RpcTransport;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
    private final Socket socket;
    private final BufferedReader reader;
    private final PrintWriter writer;
    private final Consumer<String> onDiag;

    LineFramedSocketTransport(Socket socket, Consumer<String> onDiag) throws IOException {
        this.socket = socket;
        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        this.onDiag = onDiag;
    }

    @Override
    public String readFrame() throws IOException {
        String line = reader.readLine();
        // readLine() returns null on clean EOF; an empty line is a malformed frame and is surfaced
        // to the worker, which will reply with -32700.
        return line;
    }

    @Override
    public void writeFrame(String json) {
        writer.println(json);
        if (writer.checkError()) {
            onDiag.accept("dev transport: write failed (peer closed?)");
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
