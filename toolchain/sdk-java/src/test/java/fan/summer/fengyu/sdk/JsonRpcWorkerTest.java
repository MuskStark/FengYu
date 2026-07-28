package fan.summer.fengyu.sdk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class JsonRpcWorkerTest {
    @Test void dispatchesAndReportsUnknownMethods() throws Exception {
        JsonRpcWorker worker = new JsonRpcWorker().on("hello", p -> java.util.Map.of("value", "hi " + p.get("name")));
        String input = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"hello\",\"params\":{\"name\":\"Ada\"}}\n" +
            "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"method\":\"missing\",\"params\":{}}\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        worker.run(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out);
        String result = out.toString(StandardCharsets.UTF_8);
        assertTrue(result.contains("hi Ada")); assertTrue(result.contains("-32601"));
    }

    @Test void redirectsHandlerStdoutToStderr() throws Exception {
        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream protocolOut = new ByteArrayOutputStream();
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        PrintStream protocolStream = new PrintStream(protocolOut, true, StandardCharsets.UTF_8);
        String request = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"noisy\",\"params\":{}}\n";

        try {
            System.setIn(new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)));
            System.setOut(protocolStream);
            System.setErr(new PrintStream(diagnostics, true, StandardCharsets.UTF_8));

            new JsonRpcWorker()
                .on("noisy", params -> {
                    System.out.println("library-noise");
                    return java.util.Map.of("ok", true);
                })
                .run();

            assertSame(protocolStream, System.out);
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        String[] protocolLines = protocolOut.toString(StandardCharsets.UTF_8).lines().toArray(String[]::new);
        assertEquals(1, protocolLines.length);
        assertTrue(protocolLines[0].contains("\"ok\":true"));
        assertTrue(diagnostics.toString(StandardCharsets.UTF_8).contains("library-noise"));
    }

    @Test void reportsParseAndInvalidRequestErrors() throws Exception {
        JsonRpcWorker worker = new JsonRpcWorker();
        String input = "not-json\n" +
            "{\"jsonrpc\":\"1.0\",\"id\":\"2\",\"method\":\"x\"}\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        worker.run(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out);
        String[] lines = out.toString(StandardCharsets.UTF_8).lines().toArray(String[]::new);
        assertTrue(lines[0].contains("\"code\":-32700"));
        assertTrue(lines[1].contains("\"code\":-32600"));
    }

    @Test void rejectsNullHandlers() {
        assertThrows(NullPointerException.class, () -> new JsonRpcWorker().on("x", null));
    }

    @Test void restoresStdoutWhenProtocolInputFails() {
        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        PrintStream protocolStream = new PrintStream(new ByteArrayOutputStream());

        try {
            System.setIn(new InputStream() {
                @Override public int read() throws IOException {
                    throw new IOException("read failed");
                }
            });
            System.setOut(protocolStream);

            IOException failure = assertThrows(IOException.class, () -> new JsonRpcWorker().run());

            assertEquals("read failed", failure.getMessage());
            assertSame(protocolStream, System.out);
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    /**
     * Regression: a worker ships an slf4j binding so {@code logger.info(...)} actually reaches
     * stderr (captured by the host's plugin-stderr drain). Without a binding slf4j 2.x silently
     * falls back to {@code NOPLogger} and every log call is dropped — the worker emits nothing.
     */
    @Test void slf4jCallsReachStderr() throws Exception {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        String request = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"ping\",\"params\":{}}\n";
        try {
            System.setErr(new PrintStream(diagnostics, true, StandardCharsets.UTF_8));
            Logger workerLog = LoggerFactory.getLogger("fan.summer.fengyu.sdk.test");

            new JsonRpcWorker()
                .on("ping", params -> {
                    workerLog.info("ping-seen");
                    return java.util.Map.of("ok", true);
                })
                .run(new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)),
                     new ByteArrayOutputStream());
        } finally {
            System.setErr(originalErr);
        }
        // The log line must reach stderr — proves the binding is present (NOP would drop it).
        String stderr = diagnostics.toString(StandardCharsets.UTF_8);
        assertTrue(stderr.contains("ping-seen"),
            "slf4j info() did not reach stderr — binding is missing/NOP. stderr was:\n" + stderr);
    }

    /** Handler failures retain call identity and exception type without exposing exception messages. */
    @Test void handlerExceptionsUseSafeDiagnosticsAndResponse() throws Exception {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        ByteArrayOutputStream protocol = new ByteArrayOutputStream();
        String request = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"boom\",\"params\":{}}\n";
        try {
            System.setErr(new PrintStream(diagnostics, true, StandardCharsets.UTF_8));
            new JsonRpcWorker()
                .on("boom", params -> { throw new IllegalStateException("credential=kaboom"); })
                .run(new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)),
                     protocol);
        } finally {
            System.setErr(originalErr);
        }
        String stderr = diagnostics.toString(StandardCharsets.UTF_8);
        assertTrue(stderr.contains("boom"));
        assertTrue(stderr.contains("IllegalStateException"));
        assertFalse(stderr.contains("credential=kaboom"), "exception message leaked to stderr:\n" + stderr);
        JsonObject response = JsonParser.parseString(protocol.toString(StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("credential=kaboom", response.getAsJsonObject("error").get("message").getAsString(),
            "the direct caller should retain the handler diagnostic");
    }

    /**
     * Regression (P1-2): when a handler throws, the worker must NOT log the raw JSON-RPC request
     * frame — that frame carries the full {@code params} (a password, mail body, parsed file path),
     * so logging it leaks caller secrets to stderr, which the host then forwards to its console and
     * the plugin log surface. Only the method, request id, and exception type are safe to log.
     */
    @Test void handlerFailureDoesNotLogRequestFrameOrParamSecrets() throws Exception {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        // A secret-bearing param value that is NOT echoed by the handler — it lives only in the frame.
        String request = "{\"jsonrpc\":\"2.0\",\"id\":\"req-42\",\"method\":\"save\",\"params\":{\"password\":\"super-secret-value\"}}\n";
        try {
            System.setErr(new PrintStream(diagnostics, true, StandardCharsets.UTF_8));
            new JsonRpcWorker()
                .on("save", params -> { throw new IllegalStateException("save failed"); })
                .run(new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)),
                     new ByteArrayOutputStream());
        } finally {
            System.setErr(originalErr);
        }
        String stderr = diagnostics.toString(StandardCharsets.UTF_8);
        // Diagnostics that are safe and expected: the method and id identify the failing call.
        assertTrue(stderr.contains("save"), "method must be logged for diagnostics. stderr was:\n" + stderr);
        assertTrue(stderr.contains("req-42"), "request id must be logged for diagnostics. stderr was:\n" + stderr);
        // The secret value lives ONLY in the frame/params — it must never reach stderr.
        assertFalse(stderr.contains("super-secret-value"),
            "request frame (with param secrets) leaked to stderr. stderr was:\n" + stderr);
        assertFalse(stderr.contains("\"params\""),
            "raw params object leaked to stderr. stderr was:\n" + stderr);
    }
}
