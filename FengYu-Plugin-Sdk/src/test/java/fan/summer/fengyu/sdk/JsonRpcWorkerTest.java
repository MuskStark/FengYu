package fan.summer.fengyu.sdk;

import org.junit.jupiter.api.Test;
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
}
