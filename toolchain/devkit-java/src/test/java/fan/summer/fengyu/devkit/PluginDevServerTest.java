package fan.summer.fengyu.devkit;

import fan.summer.fengyu.sdk.JsonRpcWorker;
import org.junit.jupiter.api.Test;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PluginDevServerTest {

    @Test void roundTripsJsonRpcOverLoopbackSocket() throws Exception {
        JsonRpcWorker worker = new JsonRpcWorker()
            .on("hello", p -> Map.of("message", "Hello, " + JsonRpcWorker.string(p, "name")))
            .on("add", p -> Map.of("sum", JsonRpcWorker.integer(p, "a", 0) + JsonRpcWorker.integer(p, "b", 0)));

        List<String> diag = new ArrayList<>();
        PluginDevServer server = PluginDevServer.builder()
            .worker(worker)
            .host("127.0.0.1")
            .port(0)  // ephemeral, avoid clashing with a developer's real dev server
            .onDiagnostic(diag::add)
            .start();

        try {
            int port = server.port();
            try (Socket client = new Socket("127.0.0.1", port)) {
                var out = client.getOutputStream();
                out.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"hello\",\"params\":{\"name\":\"Ada\"}}\n".getBytes(StandardCharsets.UTF_8));
                out.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"add\",\"params\":{\"a\":3,\"b\":4}}\n".getBytes(StandardCharsets.UTF_8));
                out.flush();

                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                String first = in.readLine();
                String second = in.readLine();

                assertNotNull(first, "hello response");
                assertTrue(first.contains("\"id\":1"), "response id matches request: " + first);
                assertTrue(first.contains("Hello, Ada"), "hello handler ran under the dev server: " + first);

                assertNotNull(second, "add response");
                assertTrue(second.contains("\"id\":2"), "second response id: " + second);
                assertTrue(second.contains("\"sum\":7"), "add handler ran: " + second);
            }
        } finally {
            server.close();
        }
        // The accept thread should have logged its listening banner — sanity check diagnostics wiring.
        assertTrue(diag.stream().anyMatch(m -> m.contains("listening on 127.0.0.1")),
            "dev server should announce its bound address: " + diag);
    }

    @Test void reportsUnknownMethodAsJsonRpcError() throws Exception {
        JsonRpcWorker worker = new JsonRpcWorker();  // no handlers
        PluginDevServer server = PluginDevServer.builder()
            .worker(worker)
            .port(0)
            .start();
        try {
            try (Socket client = new Socket("127.0.0.1", server.port())) {
                client.getOutputStream().write(
                    "{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"method\":\"missing\",\"params\":{}}\n".getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                String response = in.readLine();
                assertNotNull(response);
                assertTrue(response.contains("-32601"), "unknown method -> -32601: " + response);
                assertTrue(response.contains("\"id\":\"x\""));
            }
        } finally {
            server.close();
        }
    }

    @Test void workerFactoryGivesEachConnectionItsOwnWorker() throws Exception {
        // A factory that returns a worker whose handler records how many times the factory was
        // invoked. Two connections should see two distinct worker instances.
        int[] invocations = {0};
        PluginDevServer server = PluginDevServer.builder()
            .workerFactory(() -> {
                invocations[0]++;
                return new JsonRpcWorker().on("ping", p -> Map.of("worker", invocations[0]));
            })
            .port(0)
            .start();
        try {
            for (int i = 1; i <= 2; i++) {
                try (Socket client = new Socket("127.0.0.1", server.port())) {
                    client.getOutputStream().write(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{}}\n".getBytes(StandardCharsets.UTF_8));
                    client.getOutputStream().flush();
                    BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                    String response = in.readLine();
                    assertNotNull(response);
                    assertTrue(response.contains("\"worker\":" + i),
                        "connection " + i + " should hit factory worker #" + i + ": " + response);
                }
            }
            assertEquals(2, invocations[0], "factory should have been called once per connection");
        } finally {
            server.close();
        }
    }

    @Test void setsFengyuPluginRootSystemProperty() throws Exception {
        JsonRpcWorker worker = new JsonRpcWorker().on("probe", p -> Map.of("root", System.getProperty("FENGYU_PLUGIN_ROOT")));
        String previous = System.getProperty("FENGYU_PLUGIN_ROOT");
        PluginDevServer server = PluginDevServer.builder()
            .worker(worker)
            .port(0)
            .pluginRoot(Path.of("/tmp/my-plugin"))
            .start();
        try {
            try (Socket client = new Socket("127.0.0.1", server.port())) {
                client.getOutputStream().write(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"probe\",\"params\":{}}\n".getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                String response = in.readLine();
                assertNotNull(response);
                assertTrue(response.contains("/tmp/my-plugin"), "pluginRoot should be visible to the worker: " + response);
            }
        } finally {
            server.close();
            if (previous != null) System.setProperty("FENGYU_PLUGIN_ROOT", previous);
            else System.clearProperty("FENGYU_PLUGIN_ROOT");
        }
    }

    @Test void rejectsNonLoopbackHost() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> PluginDevServer.builder().port(0).start());
        assertTrue(ex.getMessage().contains("worker") || ex.getMessage().contains("workerFactory"));
        IllegalArgumentException hostEx = assertThrows(IllegalArgumentException.class,
            () -> PluginDevServer.builder().worker(new JsonRpcWorker()).host("0.0.0.0").port(0).start());
        assertTrue(hostEx.getMessage().contains("loopback"), "non-loopback host must be rejected: " + hostEx.getMessage());
    }

    @Test void requireWorkerOrFactory() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> PluginDevServer.builder().port(0).start());
        assertTrue(ex.getMessage().contains("worker"));
    }

    @Test void transportLimitsBothDirectionsByRawUtf8Bytes() throws Exception {
        byte[] frame = "😀\n".getBytes(StandardCharsets.UTF_8);
        assertEquals("😀", LineFramedSocketTransport.readBoundedLine(
            new ByteArrayInputStream(frame), 4));
        assertThrows(java.io.IOException.class, () -> LineFramedSocketTransport.readBoundedLine(
            new ByteArrayInputStream(frame), 3));
        assertDoesNotThrow(() -> LineFramedSocketTransport.ensureFrameWithinLimit(
            "😀", 4, "outbound dev frame"));
        assertThrows(java.io.IOException.class, () -> LineFramedSocketTransport.ensureFrameWithinLimit(
            "😀", 3, "outbound dev frame"));
    }
}
