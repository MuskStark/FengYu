package fan.summer.fengyu.sdk;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the {@link RpcTransport} dispatch path produces the same wire output as the legacy
 * {@link JsonRpcWorker#run(InputStream, java.io.OutputStream)} entry point, so production (stdio)
 * and development (loopback TCP) transports are observably interchangeable.
 */
class RpcTransportTest {

    @Test void stdioTransportDispatchesViaServe() throws Exception {
        JsonRpcWorker worker = new JsonRpcWorker().on("hello", p -> java.util.Map.of("value", "hi " + p.get("name")));
        String input = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"hello\",\"params\":{\"name\":\"Ada\"}}\n" +
            "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"method\":\"missing\",\"params\":{}}\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (StdioTransport transport = new StdioTransport(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out)) {
            worker.serve(transport);
        }
        String result = out.toString(StandardCharsets.UTF_8);
        assertTrue(result.contains("hi Ada"), "serve() should dispatch the hello handler");
        assertTrue(result.contains("-32601"), "serve() should report unknown methods as -32601");
        assertTrue(transportClosedFlagsMatch(result), "both requests should have produced responses");
    }

    private static boolean transportClosedFlagsMatch(String result) {
        // Two response frames = two requests fully handled.
        return result.lines().count() == 2;
    }

    @Test void serveMatchesLegacyRunInputStreamOutputStream() throws Exception {
        JsonRpcWorker worker = new JsonRpcWorker()
            .on("echo", p -> java.util.Map.of("got", p.get("x")))
            .on("boom", p -> { throw new IllegalStateException("nope"); });
        String input = "{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"echo\",\"params\":{\"x\":1}}\n" +
            "{\"jsonrpc\":\"2.0\",\"id\":\"b\",\"method\":\"boom\",\"params\":{}}\n" +
            "garbage\n" +
            "{\"jsonrpc\":\"2.0\",\"id\":\"c\",\"method\":\"echo\",\"params\":{\"x\":2}}\n";

        ByteArrayOutputStream legacyOut = new ByteArrayOutputStream();
        worker.run(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), legacyOut);
        String legacyResult = legacyOut.toString(StandardCharsets.UTF_8);

        JsonRpcWorker fresh = new JsonRpcWorker()
            .on("echo", p -> java.util.Map.of("got", p.get("x")))
            .on("boom", p -> { throw new IllegalStateException("nope"); });
        ByteArrayOutputStream serveOut = new ByteArrayOutputStream();
        try (StdioTransport t = new StdioTransport(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), serveOut)) {
            fresh.serve(t);
        }
        String serveResult = serveOut.toString(StandardCharsets.UTF_8);

        assertEquals(legacyResult, serveResult,
            "serve(RpcTransport) must be byte-for-byte identical to run(InputStream, OutputStream)");
    }

    @Test void stdioTransportReportsEofAsNullFrame() throws Exception {
        StdioTransport transport = new StdioTransport(
            new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)),
            new ByteArrayOutputStream());
        assertNull(transport.readFrame(), "empty input should read as null (clean EOF)");
        assertFalse(transport.isOpen(), "transport should report closed after EOF");
    }

    @Test void loopbackSocketTransportRoundTrips() throws Exception {
        // A minimal line-framed socket transport, exercising the same contract the devkit's
        // LineFramedSocketTransport will implement. Proves serve(RpcTransport) drives a real socket.
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            JsonRpcWorker worker = new JsonRpcWorker().on("hello", p -> java.util.Map.of("message", "Hello, " + JsonRpcWorker.string(p, "name")));
            Thread accepter = Thread.startVirtualThread(() -> {
                try (Socket conn = server.accept()) {
                    RpcTransport t = new LineFramedSocketTransport(conn);
                    worker.serve(t);
                } catch (Exception ignored) {
                    // client closed; serve() returns
                }
            });

            try (Socket client = new Socket("127.0.0.1", server.getLocalPort())) {
                client.getOutputStream().write(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"hello\",\"params\":{\"name\":\"Socket\"}}\n".getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
                byte[] buf = new byte[512];
                int read = client.getInputStream().read(buf);
                String response = new String(buf, 0, read, StandardCharsets.UTF_8);
                assertTrue(response.contains("\"message\":\"Hello, Socket\""), "socket transport must round-trip the handler response: " + response);
                assertTrue(response.contains("\"id\":1"));
            }
            accepter.join(2_000);
        }
    }

    /** Minimal in-test implementation of a socket-backed RpcTransport, mirroring the devkit's shape. */
    static final class LineFramedSocketTransport implements RpcTransport {
        private final Socket socket;
        private final java.io.BufferedReader reader;
        private final java.io.PrintWriter writer;
        LineFramedSocketTransport(Socket socket) throws java.io.IOException {
            this.socket = socket;
            this.reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new java.io.PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
        }
        @Override public String readFrame() throws java.io.IOException {
            String line = reader.readLine();
            return line;
        }
        @Override public void writeFrame(String json) { writer.println(json); }
        @Override public boolean isOpen() { return !socket.isClosed(); }
        @Override public void close() throws java.io.IOException { socket.close(); }
    }
}
