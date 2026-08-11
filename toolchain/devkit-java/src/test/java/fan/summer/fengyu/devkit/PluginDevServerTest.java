package fan.summer.fengyu.devkit;

import fan.summer.fengyu.sdk.JsonRpcWorker;
import fan.summer.fengyu.sdk.RpcError;
import fan.summer.fengyu.sdk.RpcException;
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
        // Handlers read params directly from the map (the public string()/integer() helpers were
        // removed in 1.4.0 in favour of the typed method() API); Gson exposes JSON numbers as
        // Double, so coerce via Number. The serve() loop is concurrent (T2-03), so the two
        // responses may arrive in either order — assert by content, not sequence.
        JsonRpcWorker worker = new JsonRpcWorker()
            .on("hello", p -> Map.of("message", "Hello, " + String.valueOf(p.get("name"))))
            .on("add", p -> Map.of("sum", ((Number) p.get("a")).intValue() + ((Number) p.get("b")).intValue()));

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
                List<String> responses = new ArrayList<>();
                responses.add(in.readLine());
                responses.add(in.readLine());

                // Both handlers must have run; match each payload to its response regardless of order.
                String hello = responses.stream().filter(r -> r.contains("\"id\":1")).findFirst().orElse(null);
                String add = responses.stream().filter(r -> r.contains("\"id\":2")).findFirst().orElse(null);
                assertNotNull(hello, "hello response present: " + responses);
                assertNotNull(add, "add response present: " + responses);
                assertTrue(hello.contains("Hello, Ada"), "hello handler ran under the dev server: " + hello);
                assertTrue(add.contains("\"sum\":7"), "add handler ran: " + add);
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

    @Test void setsFengyuPluginIdSystemProperty() throws Exception {
        // Mirrors the ROOT test: the devkit exposes the plugin id via FENGYU_PLUGIN_ID so the
        // worker sees the same identity the production host injects via its env var.
        JsonRpcWorker worker = new JsonRpcWorker().on("probe", p -> Map.of("id", System.getProperty("FENGYU_PLUGIN_ID")));
        String previous = System.getProperty("FENGYU_PLUGIN_ID");
        PluginDevServer server = PluginDevServer.builder()
            .worker(worker)
            .port(0)
            .pluginId("com.example.my-plugin")
            .start();
        try {
            try (Socket client = new Socket("127.0.0.1", server.port())) {
                client.getOutputStream().write(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"probe\",\"params\":{}}\n".getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                String response = in.readLine();
                assertNotNull(response);
                assertTrue(response.contains("com.example.my-plugin"),
                    "pluginId should be visible to the worker: " + response);
            }
        } finally {
            server.close();
            if (previous != null) System.setProperty("FENGYU_PLUGIN_ID", previous);
            else System.clearProperty("FENGYU_PLUGIN_ID");
        }
    }

    @Test void cancelRequestOverSocketIsHandledByServeLoop() throws Exception {
        // Bullet 2b: a $/cancelRequest notification sent to the devkit socket must be honoured by
        // JsonRpcWorker.serve() (the SAME loop production uses over stdio). The cancelled call
        // returns a structured CANCELLED error envelope (numeric -32800 + data.code "CANCELLED"),
        // never a worker crash. The cancel notification itself is a JSON-RPC notification (no id)
        // and produces no response.
        JsonRpcWorker worker = new JsonRpcWorker().on("slow", p -> {
            try {
                Thread.sleep(30_000); // would only complete if cancel FAILS
                return Map.of("done", true);
            } catch (InterruptedException ie) {
                // Honour the interrupt serve() issued via the cancellation token and surface a
                // clean CANCELLED error rather than letting the pool swallow it.
                Thread.currentThread().interrupt();
                throw new RpcException(RpcError.Code.CANCELLED, "request cancelled");
            }
        });
        PluginDevServer server = PluginDevServer.builder().worker(worker).port(0).start();
        try {
            try (Socket client = new Socket("127.0.0.1", server.port())) {
                client.setSoTimeout(5_000);
                var out = client.getOutputStream();
                out.write("{\"jsonrpc\":\"2.0\",\"id\":\"c1\",\"method\":\"slow\",\"params\":{}}\n".getBytes(StandardCharsets.UTF_8));
                out.write("{\"jsonrpc\":\"2.0\",\"method\":\"$/cancelRequest\",\"params\":{\"id\":\"c1\"}}\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                String response = in.readLine();
                assertNotNull(response, "cancelled call must still produce a response");
                assertTrue(response.contains("\"id\":\"c1\""), "response correlated to the cancelled call: " + response);
                assertTrue(response.contains("\"error\""), "cancelled call is an error, not a result: " + response);
                assertTrue(response.contains("-32800"), "CANCELLED numeric JSON-RPC code: " + response);
                assertTrue(response.contains("\"code\":\"CANCELLED\""),
                    "structured error.data.code label (matches production RpcError.Code): " + response);
            }
        } finally {
            server.close();
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
