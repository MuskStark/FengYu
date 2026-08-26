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
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertEquals(List.of("echo", "env"), server.tools());
        assertEquals(30, server.requestTimeoutSeconds());
        assertEquals(30, server.initTimeoutSeconds());
        assertEquals("fixture", server.toolPrefix());
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
    void disabledToolsPatternsHideToolsFromTheAiCatalogOnly() {
        McpRuntimeManager manager = new McpRuntimeManager(temp);
        String classPath = System.getProperty("java.class.path");
        McpRuntimeManager.ServerView server = manager.save(new McpRuntimeManager.ServerRequest(
                "filtered", "STDIO", "java",
                List.of("-cp", classPath, McpTestServerMain.class.getName()),
                Map.of(), null, null, Map.of(), true,
                List.of("env"), 45, 90), null);

        assertEquals(45, server.requestTimeoutSeconds());
        assertEquals(90, server.initTimeoutSeconds());
        // The server still lists both tools; only the AI-facing catalog drops the disabled one.
        assertEquals(List.of("echo", "env"), server.tools());
        assertEquals(1, manager.callbacks().size());
        assertTrue(manager.callbacks().getFirst().getToolDefinition().name().endsWith("__echo"));
        // A wildcard for the whole server hides everything.
        McpRuntimeManager.ServerView wildcard = manager.save(new McpRuntimeManager.ServerRequest(
                "filtered", "STDIO", "java",
                List.of("-cp", classPath, McpTestServerMain.class.getName()),
                Map.of(), null, null, Map.of(), true,
                List.of("*"), null, null), server.id());
        assertEquals(List.of("*"), wildcard.disabledTools());
        assertTrue(manager.callbacks().isEmpty());
        manager.stop();
    }

    @Test
    void toolNamesAreNamespacedPerServerSoCollisionsCannotShadowEachOther() {
        McpRuntimeManager manager = new McpRuntimeManager(temp);
        String classPath = System.getProperty("java.class.path");
        manager.save(new McpRuntimeManager.ServerRequest(
                "alpha", "STDIO", "java",
                List.of("-cp", classPath, McpTestServerMain.class.getName()),
                Map.of(), null, null, Map.of(), true), null);
        manager.save(new McpRuntimeManager.ServerRequest(
                "beta", "STDIO", "java",
                List.of("-cp", classPath, McpTestServerMain.class.getName()),
                Map.of(), null, null, Map.of(), true), null);

        List<String> names = manager.callbacks().stream()
                .map(callback -> callback.getToolDefinition().name()).sorted().toList();
        assertTrue(names.contains("alpha__echo"));
        assertTrue(names.contains("beta__echo"));
        assertTrue(names.contains("alpha__env"));
        assertTrue(names.contains("beta__env"));
        manager.stop();
    }

    @Test
    void deniedEnvKeysNeverReachTheStdioServerProcess() {
        McpRuntimeManager manager = new McpRuntimeManager(temp);
        String classPath = System.getProperty("java.class.path");
        McpRuntimeManager.ServerView server = manager.save(new McpRuntimeManager.ServerRequest(
                "injected", "STDIO", "java",
                List.of("-cp", classPath, McpTestServerMain.class.getName()),
                Map.of("NODE_OPTIONS", "--require=evil.js", "LD_PRELOAD", "/tmp/evil.so", "OK_KEY", "ok-value"),
                null, null, Map.of(), true), null);

        String nodeOptions = manager.call(server.id(), "env", Map.of("key", "NODE_OPTIONS")).toString();
        String ldPreload = manager.call(server.id(), "env", Map.of("key", "LD_PRELOAD")).toString();
        String ok = manager.call(server.id(), "env", Map.of("key", "OK_KEY")).toString();
        assertTrue(nodeOptions.contains("<unset>"));
        assertTrue(ldPreload.contains("<unset>"));
        assertTrue(ok.contains("ok-value"));
        manager.stop();
    }

    @Test
    void importsMcpServersFromPluginConfigFilesAsDisabledUntilAdopted() throws Exception {
        Path mcpDir = temp.resolve("mcp-servers");
        Files.createDirectories(mcpDir);
        String classPath = System.getProperty("java.class.path");
        Files.writeString(mcpDir.resolve("slug-claude:CLAUDE:demo.json"), """
                {
                  "local-fixture": {
                    "command": "java",
                    "args": ["-cp", %s, %s],
                    "env": {"NODE_OPTIONS": "--require=evil.js", "OK_KEY": "ok-value"}
                  },
                  "remote": {"url": "http://127.0.0.1:12345/mcp", "type": "http"}
                }
                """.formatted(quote(classPath), quote(McpTestServerMain.class.getName())));

        McpRuntimeManager manager = new McpRuntimeManager(temp);
        manager.start();
        List<McpRuntimeManager.ServerView> servers = manager.servers();
        assertEquals(2, servers.size());
        McpRuntimeManager.ServerView local = servers.stream()
                .filter(server -> server.name().equals("local-fixture")).findFirst().orElseThrow();
        McpRuntimeManager.ServerView remote = servers.stream()
                .filter(server -> server.name().equals("remote")).findFirst().orElseThrow();

        assertFalse(local.enabled());
        assertEquals("slug-claude:CLAUDE:demo", local.source());
        assertTrue(manager.callbacks().isEmpty());
        assertEquals("STREAMABLE_HTTP", remote.type());
        assertEquals("http://127.0.0.1:12345/", remote.url());
        assertEquals("/mcp", remote.endpoint());

        // Imported-but-not-adopted servers come from the plugin; deleting must route through uninstall.
        assertThrows(McpRuntimeManager.McpRuntimeException.class, () -> manager.delete(local.id()));

        // Testing works (transient session), still without entering the live registry.
        assertEquals("connected", manager.test(local.id()).status());
        assertTrue(manager.callbacks().isEmpty());

        // Enabling adopts the server into the user-managed registry with its plugin origin kept,
        // and the imported env survives, minus the denied interpreter-injection keys.
        McpRuntimeManager.ServerView adopted = manager.save(new McpRuntimeManager.ServerRequest(
                local.name(), local.type(), local.command(), local.args(), null,
                local.url(), local.endpoint(), Map.of(), true), local.id());
        assertTrue(adopted.enabled());
        assertEquals("slug-claude:CLAUDE:demo", adopted.source());
        assertEquals(2, manager.callbacks().size());
        String nodeOptions = manager.call(adopted.id(), "env", Map.of("key", "NODE_OPTIONS")).toString();
        String okKey = manager.call(adopted.id(), "env", Map.of("key", "OK_KEY")).toString();
        assertTrue(nodeOptions.contains("<unset>"));
        assertTrue(okKey.contains("ok-value"));
        manager.stop();

        // An adopted server survives a restart even after the plugin (and its file) is gone.
        Files.delete(mcpDir.resolve("slug-claude:CLAUDE:demo.json"));
        McpRuntimeManager restarted = new McpRuntimeManager(temp);
        restarted.start();
        assertEquals(1, restarted.servers().size());
        assertEquals("local-fixture", restarted.servers().getFirst().name());
        assertTrue(restarted.delete(restarted.servers().getFirst().id()));
        restarted.stop();
    }

    @Test
    void toolDisablePatternsMatchBareWireAndWildcardForms() {
        List<String> patterns = List.of("env");
        assertTrue(McpRuntimeManager.isToolDisabled("myserver__env", patterns));
        assertTrue(McpRuntimeManager.isToolDisabled("env", patterns));
        assertFalse(McpRuntimeManager.isToolDisabled("myserver__echo", patterns));
        assertTrue(McpRuntimeManager.isToolDisabled("myserver__echo",
                List.of("myserver__*")));
        assertFalse(McpRuntimeManager.isToolDisabled("otherserver__echo",
                List.of("myserver__*")));
        assertTrue(McpRuntimeManager.isToolDisabled("anything", List.of("*")));
        assertFalse(McpRuntimeManager.isToolDisabled("anything", List.of("  ")));
        assertFalse(McpRuntimeManager.isToolDisabled("anything", List.of()));
    }

    @Test
    void timeoutValuesAreClampedToASaneRange() {
        McpRuntimeManager manager = new McpRuntimeManager(temp);
        McpRuntimeManager.ServerView server = manager.save(new McpRuntimeManager.ServerRequest(
                "clamped", "STREAMABLE_HTTP", null, List.of(), Map.of(),
                "http://127.0.0.1:12345", "/mcp", Map.of(), false,
                List.of(), 9_999, 1), null);
        assertEquals(600, server.requestTimeoutSeconds());
        assertEquals(5, server.initTimeoutSeconds());
        manager.stop();
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

    @Test
    void unreadableRegistryDoesNotPreventHostStartup() throws Exception {
        Path registry = temp.resolve("mcp-servers").resolve("servers.json");
        Files.createDirectories(registry.getParent());
        Files.writeString(registry, "[{\"id\":");

        McpRuntimeManager manager = new McpRuntimeManager(temp);
        assertTrue(manager.servers().isEmpty());
        manager.start();
        assertTrue(manager.servers().isEmpty());
        manager.stop();
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
                                "result", Map.of("tools", List.of(
                                        Map.of("name", "echo",
                                                "description", "returns a fixture value",
                                                "inputSchema", Map.of("type", "object")),
                                        Map.of("name", "env",
                                                "description", "returns one process env value",
                                                "inputSchema", Map.of("type", "object")))))));
                    } else if ("tools/call".equals(method)) {
                        Map<?, ?> params = (Map<?, ?>) request.get("params");
                        String tool = String.valueOf(params.get("name"));
                        Map<?, ?> arguments = params.get("arguments") instanceof Map<?, ?> map ? map : Map.of();
                        String text;
                        if ("env".equals(tool)) {
                            String key = String.valueOf(arguments.get("key"));
                            String value = System.getenv(key);
                            text = value == null ? key + "=<unset>" : key + "=" + value;
                        } else {
                            text = "fixture-ready";
                        }
                        out.println(json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id,
                                "result", Map.of("content", List.of(Map.of("type", "text", "text", text)),
                                        "isError", false))));
                    }
                }
            }
        }
    }

    private static String quote(String value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException(error);
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
    /** M-1: the stdio overlay neutralizes inherited host credentials; configured keys win. */
    @Test
    void stdioChildEnvNeutralizesInheritedHostSecrets() {
        java.util.Map<String, String> hostEnv = java.util.Map.of(
                "PATH", "/usr/bin",
                "FENGYU_AUTH_TOKEN", "zf-primary-secret",
                "FENGYU_BROWSER_BRIDGE_TOKEN", "bridge-secret",
                "OPENAI_API_KEY", "sk-inherited");
        java.util.Map<String, String> configured = java.util.Map.of(
                "PLUGIN_API_TOKEN", "explicitly-configured");

        java.util.Map<String, String> child =
                McpRuntimeManager.childEnvWithNeutralizedHostSecrets(configured, hostEnv);

        assertEquals("", child.get("FENGYU_AUTH_TOKEN"),
                "the inherited primary token must be neutralized, not passed through");
        assertEquals("", child.get("FENGYU_BROWSER_BRIDGE_TOKEN"));
        assertEquals("", child.get("OPENAI_API_KEY"));
        assertEquals("explicitly-configured", child.get("PLUGIN_API_TOKEN"),
                "an operator-configured key keeps its configured value");
    }

}
