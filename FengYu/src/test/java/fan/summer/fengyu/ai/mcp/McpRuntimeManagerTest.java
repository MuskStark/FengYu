package fan.summer.fengyu.ai.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpRuntimeManagerTest {

    @TempDir Path temp;

    @Test
    void addsConnectsDiscoversAndCallsStdioServerWithoutRestartingHost() {
        McpRuntimeManager manager = new McpRuntimeManager(temp);
        String classPath = System.getProperty("java.class.path");
        McpRuntimeManager.ServerView server = manager.save(new McpRuntimeManager.ServerRequest(
                "fixture", "STDIO", "java",
                List.of("-cp", classPath, McpTestServerMain.class.getName()),
                Map.of(), null, null, Map.of(), true), null);

        assertEquals("connected", server.status());
        assertEquals(List.of("echo"), server.tools());
        assertTrue(manager.callbacks().getFirst().call("{}").contains("fixture-ready"));
        assertTrue(manager.call(server.id(), "echo", Map.of()).toString().contains("fixture-ready"));

        manager.stop();
        McpRuntimeManager restarted = new McpRuntimeManager(temp);
        restarted.start();
        assertEquals("connected", restarted.servers().getFirst().status());
        assertTrue(restarted.delete(server.id()));
        assertTrue(restarted.servers().isEmpty());
        restarted.stop();
    }

    @Test
    void testingDisabledServerDoesNotLeaveItInLiveToolRegistry() {
        McpRuntimeManager manager = new McpRuntimeManager(temp);
        String classPath = System.getProperty("java.class.path");
        McpRuntimeManager.ServerView server = manager.save(new McpRuntimeManager.ServerRequest(
                "disabled-fixture", "STDIO", "java",
                List.of("-cp", classPath, McpTestServerMain.class.getName()),
                Map.of(), null, null, Map.of(), false), null);

        assertEquals("disconnected", server.status());
        assertEquals("connected", manager.test(server.id()).status());
        assertEquals("disconnected", manager.servers().getFirst().status());
        assertTrue(manager.callbacks().isEmpty());
        manager.stop();
    }

    @Test
    void connectsToStreamableHttpServerUsingMcpChromeStyleEndpoint() throws Exception {
        try (StreamableHttpFixture fixture = new StreamableHttpFixture()) {
            McpRuntimeManager manager = new McpRuntimeManager(temp);
            McpRuntimeManager.ServerView server = manager.save(new McpRuntimeManager.ServerRequest(
                    "mcp-chrome", "STREAMABLE_HTTP", null, List.of(), Map.of(),
                    fixture.url().toString(), "/mcp", Map.of(), true), null);

            assertEquals("connected", server.status());
            assertEquals(List.of("chrome_navigate"), server.tools());
            assertTrue(manager.call(server.id(), "chrome_navigate", Map.of("url", "https://example.com"))
                    .toString().contains("chrome-fixture-ready"));
            manager.stop();
        }
    }

    /** Minimal newline-delimited JSON-RPC fixture; it exercises the real MCP SDK transport. */
    public static final class McpTestServerMain {
        public static void main(String[] args) throws Exception {
            ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
                    PrintWriter out = new PrintWriter(System.out, true)) {
                String line;
                while ((line = in.readLine()) != null) {
                    Map<?, ?> request = json.readValue(line, Map.class);
                    Object id = request.get("id");
                    String method = String.valueOf(request.get("method"));
                    if (id == null) continue;
                    if ("initialize".equals(method)) {
                        out.println(json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id,
                                "result", Map.of("protocolVersion", "2025-03-26",
                                        "capabilities", Map.of("tools", Map.of()),
                                        "serverInfo", Map.of("name", "fixture", "version", "1")))));
                    } else if ("tools/list".equals(method)) {
                        out.println(json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id,
                                "result", Map.of("tools", List.of(Map.of("name", "echo",
                                        "description", "returns a fixture value",
                                        "inputSchema", Map.of("type", "object")))))));
                    } else if ("tools/call".equals(method)) {
                        out.println(json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id,
                                "result", Map.of("content", List.of(Map.of("type", "text", "text", "fixture-ready")),
                                        "isError", false))));
                    }
                }
            }
        }
    }

    /** Stateless Streamable HTTP fixture matching the endpoint advertised by mcp-chrome. */
    private static final class StreamableHttpFixture implements AutoCloseable {
        private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
        private final HttpServer server;

        StreamableHttpFixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mcp", this::handle);
            server.start();
        }

        URI url() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                Map<?, ?> request = json.readValue(exchange.getRequestBody(), Map.class);
                Object id = request.get("id");
                String method = String.valueOf(request.get("method"));
                Map<String, Object> result = switch (method) {
                    case "initialize" -> Map.of("protocolVersion", "2025-03-26",
                            "capabilities", Map.of("tools", Map.of()),
                            "serverInfo", Map.of("name", "mcp-chrome-fixture", "version", "1"));
                    case "notifications/initialized" -> null;
                    case "tools/list" -> Map.of("tools", List.of(Map.of("name", "chrome_navigate",
                            "description", "navigates a Chrome tab", "inputSchema", Map.of("type", "object"))));
                    case "tools/call" -> Map.of("content", List.of(Map.of("type", "text", "text", "chrome-fixture-ready")),
                            "isError", false);
                    default -> Map.of();
                };
                if (id == null || result == null) {
                    exchange.sendResponseHeaders(202, -1);
                    return;
                }
                byte[] body = json.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", id, "result", result));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
