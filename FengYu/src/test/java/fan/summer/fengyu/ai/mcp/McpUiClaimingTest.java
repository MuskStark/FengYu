package fan.summer.fengyu.ai.mcp;

import fan.summer.fengyu.ai.mcp.apps.McpSandboxRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP Apps "claiming": an app-only tool ({@code visibility: ["app"]}) stays out of the
 * model's tool catalog until a sandbox page for its server is live, and drops out again
 * when the page closes. Driven through the production path — the sandbox registry's
 * page-count reports — against a real stdio MCP connection.
 */
class McpUiClaimingTest {

    @TempDir
    Path temp;

    @Test
    void appOnlyToolsJoinAndLeaveTheCatalogWithOpenPages() throws Exception {
        McpRuntimeManager manager = new McpRuntimeManager(temp);
        McpSandboxRegistry sandboxes = new McpSandboxRegistry(manager);
        String classPath = System.getProperty("java.class.path");
        McpRuntimeManager.ServerView server = manager.save(new McpRuntimeManager.ServerRequest(
                "ui-fixture", "STDIO", "java",
                List.of("-cp", classPath, McpUiServerMain.class.getName()),
                Map.of(), null, null, Map.of(), true), null);

        assertEquals("connected", server.status());
        // Both tools exist on the server; the model catalog hides the app-only one.
        assertTrue(server.tools().containsAll(List.of("app_tool", "open_tool")));
        assertEquals(1, manager.uiTools(server.id()).size());
        assertTrue(visibleNames(manager).stream().noneMatch(name -> name.endsWith("__app_tool")),
                "app-only tool must be hidden before any page opens");

        // A prepared page claims the app-only tool for the model catalog…
        McpSandboxRegistry.SandboxEntry page = sandboxes.register(server.id(), "UI Fixture",
                "ui://app/main.html", "tool:call-1", "<html></html>", null, null);
        assertTrue(visibleNames(manager).stream().anyMatch(name -> name.endsWith("__app_tool")),
                "opening a page must claim the app-only tool");

        // …and closing it (explicit dispose) unclaims it again.
        assertTrue(sandboxes.dispose(page.sandboxId()));
        assertTrue(visibleNames(manager).stream().noneMatch(name -> name.endsWith("__app_tool")),
                "closing the page must unclaim the app-only tool");

        manager.stop();
    }

    private static List<String> visibleNames(McpRuntimeManager manager) {
        return manager.callbacks().stream()
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toList());
    }

    /**
     * Newline-delimited JSON-RPC fixture declaring one model tool and one app-only tool
     * (a {@code _meta.ui} descriptor with {@code visibility: ["app"]}).
     */
    public static final class McpUiServerMain {
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
                                        "serverInfo", Map.of("name", "ui-fixture", "version", "1")))));
                    } else if ("tools/list".equals(method)) {
                        out.println(json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id,
                                "result", Map.of("tools", List.of(
                                        Map.of("name", "open_tool",
                                                "description", "always visible to the model",
                                                "inputSchema", Map.of("type", "object")),
                                        Map.of("name", "app_tool",
                                                "description", "only the app may call it",
                                                "inputSchema", Map.of("type", "object"),
                                                "_meta", Map.of("ui", Map.of(
                                                        "resourceUri", "ui://app/main.html",
                                                        "visibility", List.of("app")))))))));
                    } else if ("tools/call".equals(method)) {
                        out.println(json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id,
                                "result", Map.of("content", List.of(Map.of("type", "text", "text", "ui-fixture-ready")),
                                        "isError", false))));
                    }
                }
            }
        }
    }
}
