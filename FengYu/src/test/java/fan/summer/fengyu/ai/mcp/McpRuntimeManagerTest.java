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
import static org.junit.jupiter.api.Assertions.fail;

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

    /** Wildcards match whole words only: `acc*` must not disable `account`. */
    @Test
    void wildcardsMatchWholeToolNamesNotSharedPrefixes() {
        // `acc*` covers the word `acc` and words that START a new segment after `acc` …
        assertTrue(McpRuntimeManager.isToolDisabled("myserver__acc", List.of("acc*")));
        assertTrue(McpRuntimeManager.isToolDisabled("myserver__acc_lookup", List.of("acc*")));
        assertTrue(McpRuntimeManager.isToolDisabled("myserver__acc.lookup", List.of("acc*")));
        // … but NOT names that merely share the character prefix.
        assertFalse(McpRuntimeManager.isToolDisabled("myserver__account", List.of("acc*")));
        assertFalse(McpRuntimeManager.isToolDisabled("account", List.of("acc*")));
        // A stem that itself ends on the separator covers everything after it (the
        // server-wide wildcard stays a full-server wildcard).
        assertTrue(McpRuntimeManager.isToolDisabled("myserver__anything", List.of("myserver__*")));
        assertTrue(McpRuntimeManager.isToolDisabled("myserver__account", List.of("account*")));
        assertFalse(McpRuntimeManager.isToolDisabled("myserver__accountancy", List.of("account*")));
    }

    /** Backoff: 30s doubling to the 10-minute cap. */
    @Test
    void reconnectBackoffDoublesPerFailureAndCaps() {
        assertEquals(java.time.Duration.ofSeconds(30).toNanos(),
                McpRuntimeManager.backoffDelayNanos(
                        java.time.Duration.ofSeconds(30), java.time.Duration.ofMinutes(10), 1));
        assertEquals(java.time.Duration.ofMinutes(1).toNanos(),
                McpRuntimeManager.backoffDelayNanos(
                        java.time.Duration.ofSeconds(30), java.time.Duration.ofMinutes(10), 2));
        assertEquals(java.time.Duration.ofMinutes(8).toNanos(),
                McpRuntimeManager.backoffDelayNanos(
                        java.time.Duration.ofSeconds(30), java.time.Duration.ofMinutes(10), 5));
        assertEquals(java.time.Duration.ofMinutes(10).toNanos(),
                McpRuntimeManager.backoffDelayNanos(
                        java.time.Duration.ofSeconds(30), java.time.Duration.ofMinutes(10), 6));
        assertEquals(java.time.Duration.ofMinutes(10).toNanos(),
                McpRuntimeManager.backoffDelayNanos(
                        java.time.Duration.ofSeconds(30), java.time.Duration.ofMinutes(10), 100),
                "the backoff ceiling must hold however often the server fails");
    }

    /** P2-4: an error-state server self-heals via the background sweep once it comes back. */
    @Test
    void errorServersReconnectWithBackoffWhenTheyComeBack() throws Exception {
        Path gate = temp.resolve("gate");
        McpRuntimeManager failing = new McpRuntimeManager(temp, fastTimings());
        McpRuntimeManager.ServerView broken = failing.save(new McpRuntimeManager.ServerRequest(
                "gated", "STDIO", "java",
                List.of("-cp", System.getProperty("java.class.path"),
                        McpGatedServerMain.class.getName(), gate.toString()),
                Map.of(), null, null, Map.of(), true), null);
        assertEquals("error", broken.status());
        assertTrue(failing.callbacks().isEmpty());
        failing.stop(); // persists servers.json; the healed instance below starts fresh

        McpRuntimeManager manager = new McpRuntimeManager(temp, fastTimings());
        // Startup connect fails again (gate still absent) → error; the sweep stays armed.
        manager.start();
        assertEquals("error", manager.servers().getFirst().status());
        // Server comes back → the sweep reconnects it with the (tiny) backoff delay.
        Files.createFile(gate);
        awaitStatus(manager, "connected");
        assertFalse(manager.callbacks().isEmpty());
        assertTrue(manager.callbacks().stream()
                .anyMatch(callback -> callback.getToolDefinition().name().equals("gated__echo")));
        manager.stop();
    }

    /**
     * P2-4: a tool call against a server that died mid-session invalidates the dead
     * connection and triggers one async rebuild — the next call works again without any
     * manual Test.
     */
    @Test
    void callFailureOnDeadConnectionInvalidatesAndRebuildsTheServer() throws Exception {
        Path gate = temp.resolve("gate");
        Files.createFile(gate);
        McpRuntimeManager manager = new McpRuntimeManager(temp, fastTimings());
        McpRuntimeManager.ServerView server = manager.save(new McpRuntimeManager.ServerRequest(
                "mortal", "STDIO", "java",
                List.of("-cp", System.getProperty("java.class.path"),
                        McpGatedServerMain.class.getName(), gate.toString()),
                Map.of(), null, null, Map.of(), true), null);
        assertEquals("connected", server.status());
        assertEquals(List.of("die", "echo"), server.tools());
        assertTrue(manager.call(server.id(), "echo", Map.of()).toString().contains("gated-ready"));

        // The `die` tool exits the server process; the call throws and the runtime marks
        // the connection dead and schedules a rebuild.
        assertThrows(RuntimeException.class, () -> manager.call(server.id(), "die", Map.of()));
        awaitStatus(manager, "connected");
        assertTrue(manager.call(server.id(), "echo", Map.of()).toString().contains("gated-ready"));
        manager.stop();
    }

    /** P2-5: startup connects under one shared budget — slow servers are abandoned, not waited for. */
    @Test
    void startupConnectIsParallelWithATotalBudget() throws Exception {
        McpRuntimeManager slow = new McpRuntimeManager(temp, fastTimings());
        // initTimeoutSeconds=1 keeps the two save() calls from each burning the 30s
        // default handshake timeout — the budget under test is start()'s, not save()'s.
        slow.save(new McpRuntimeManager.ServerRequest("slow-a", "STDIO", "sleep",
                List.of("30"), Map.of(), null, null, Map.of(), true,
                List.of(), 5, 1), null);
        slow.save(new McpRuntimeManager.ServerRequest("slow-b", "STDIO", "sleep",
                List.of("30"), Map.of(), null, null, Map.of(), true,
                List.of(), 5, 1), null);
        slow.stop(); // both persisted

        McpRuntimeManager manager = new McpRuntimeManager(temp, new McpRuntimeManager.ReconnectTimings(
                java.time.Duration.ofMillis(400), null, null, null));
        long startedAt = System.nanoTime();
        manager.start();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        assertTrue(elapsedMs < 10_000, "startup must respect the shared connect budget (took "
                + elapsedMs + "ms for two 30s servers)");
        for (McpRuntimeManager.ServerView server : manager.servers()) {
            assertEquals("error", server.status(), "budget-missed servers land in error state");
            assertTrue(manager.callbacks().isEmpty());
        }
        manager.stop();
    }

    /** Fast reconnect timings for the self-healing tests. */
    private static McpRuntimeManager.ReconnectTimings fastTimings() {
        return new McpRuntimeManager.ReconnectTimings(
                java.time.Duration.ofSeconds(5),
                java.time.Duration.ofMillis(50),
                java.time.Duration.ofSeconds(1),
                java.time.Duration.ofMillis(100));
    }

    /** Polls the server views until the single persisted server reaches the wanted status. */
    private static void awaitStatus(McpRuntimeManager manager, String wanted) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (manager.servers().stream().anyMatch(server -> wanted.equals(server.status()))) return;
            Thread.sleep(50);
        }
        fail("server never reached status " + wanted + "; views=" + manager.servers());
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

    /**
     * Same newline-delimited JSON-RPC fixture as {@link McpTestServerMain}, but with a
     * lifecycle: {@code args[0]} is a gate file — the server refuses to boot while it is
     * absent (connect fails), exposes {@code die} (exits the process without answering, so
     * the call fails) and {@code echo} (answers "gated-ready"). This drives the reconnect
     * and dead-connection tests above.
     */
    public static final class McpGatedServerMain {
        public static void main(String[] args) throws Exception {
            if (!Files.exists(Path.of(args[0]))) return; // refuses to start → connect error
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
                                        "serverInfo", Map.of("name", "gated", "version", "1")))));
                    } else if ("tools/list".equals(method)) {
                        out.println(json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id,
                                "result", Map.of("tools", List.of(
                                        Map.of("name", "die",
                                                "description", "exits the server process",
                                                "inputSchema", Map.of("type", "object")),
                                        Map.of("name", "echo",
                                                "description", "returns a fixture value",
                                                "inputSchema", Map.of("type", "object")))))));
                    } else if ("tools/call".equals(method)) {
                        Map<?, ?> params = (Map<?, ?>) request.get("params");
                        String tool = String.valueOf(params.get("name"));
                        if ("die".equals(tool)) {
                            // No response: the host's pending call must fail on the dead pipe.
                            System.exit(0);
                        }
                        out.println(json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id,
                                "result", Map.of("content", List.of(Map.of("type", "text", "text", "gated-ready")),
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
