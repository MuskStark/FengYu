package fan.summer.fengyu.sdk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class JsonRpcWorkerTest {
    @Test void closesLifecycleResourcesInReverseOrderExactlyOnce() throws Exception {
        List<String> closed = new ArrayList<>();
        JsonRpcWorker worker = new JsonRpcWorker()
            .onClose(() -> closed.add("first"))
            .onClose(() -> closed.add("second"));
        worker.run(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
        assertEquals(List.of("second", "first"), closed);
        assertThrows(IllegalStateException.class, () -> worker.onClose(() -> {}));
    }
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
                // run() forces a JVM exit once stdin EOFs; stub it so the test JVM survives.
                .withExitHandler(c -> {})
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

            IOException failure = assertThrows(IOException.class,
                () -> new JsonRpcWorker().withExitHandler(c -> {}).run());

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

    // ── 1.2.0 parent-death watchdog ──────────────────────────────────────────

    /**
     * Primary watchdog path: when stdin reaches EOF the dispatch loop returns cleanly, and run()
     * must force a JVM exit so a worker with lingering non-daemon threads cannot survive its host.
     */
    @Test void runExitsJvmOnStdinEof() throws Exception {
        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        AtomicInteger exitCalls = new AtomicInteger();
        String input = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"ping\",\"params\":{}}\n";
        try {
            System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
            System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            new JsonRpcWorker()
                .on("ping", p -> java.util.Map.of("ok", true))
                .withExitHandler(c -> exitCalls.incrementAndGet())
                // Always-alive parent so the auxiliary watchdog never fires; this isolates the EOF path.
                .run((BooleanSupplier) () -> true);
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
        assertEquals(1, exitCalls.get(),
            "run() must call exitWorker(0) once stdin EOF ends the dispatch loop");
    }

    /**
     * Auxiliary watchdog path: if the parent process disappears while serve() is still blocked on
     * stdin, the parent-liveness thread must exit the worker. Here stdin never EOFs (it blocks
     * forever), so only the watchdog can end the worker. The probe reports the parent as gone after
     * the first poll; exitHandler is intercepted so the test JVM survives.
     *
     * <p>run() runs on a background thread because production exitHandler (System.exit) would halt
     * the JVM; with it intercepted, serve() stays blocked and run() never returns. We assert the
     * watchdog fired by polling the captured exit counter, then interrupt to unwind.
     */
    @Test void runExitsJvmWhenParentProcessDies() throws Exception {
        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        AtomicInteger exitCalls = new AtomicInteger();
        Thread runner = new Thread(() -> {
            try {
                new JsonRpcWorker()
                    .withExitHandler(c -> exitCalls.incrementAndGet())
                    .run((BooleanSupplier) () -> false);
            } catch (Exception ignored) {}
        }, "watchdog-test-runner");
        try {
            // A stdin that never yields a frame: keeps serve() blocked so the EOF path cannot fire.
            System.setIn(new InputStream() {
                @Override public int read() {
                    try { Thread.sleep(60_000); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return -1;
                }
            });
            System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            runner.setDaemon(true);
            runner.start();
            // The probe always reports "dead"; the watchdog skips the first poll then fires exit.
            long deadline = System.currentTimeMillis() + 5_000;
            while (exitCalls.get() < 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(exitCalls.get() >= 1,
                "auxiliary parent-death watchdog should call exitHandler within 5s");
        } finally {
            runner.interrupt();
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }
}
