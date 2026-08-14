package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.mcp.McpRuntimeManager;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP connection management and diagnostics. Dynamic connections are owned by
 * {@link McpRuntimeManager}; the legacy Spring AI startup connections remain visible in the
 * status response for backwards compatibility.
 */
@RestController
public class McpController {

    private final ObjectProvider<List<McpSyncClient>> clients;
    private final ObjectProvider<SyncMcpToolCallbackProvider> tools;
    private final McpRuntimeManager runtime;
    private final boolean enabled;

    public McpController(
            @Qualifier("mcpSyncClients") ObjectProvider<List<McpSyncClient>> clients,
            ObjectProvider<SyncMcpToolCallbackProvider> tools,
            McpRuntimeManager runtime,
            @Value("${spring.ai.mcp.client.enabled:true}") boolean enabled) {
        this.clients = clients;
        this.tools = tools;
        this.runtime = runtime;
        this.enabled = enabled;
    }

    @GetMapping("/api/mcp/status")
    public Map<String, Object> status() {
        List<Map<String, Object>> connections = new ArrayList<>();
        for (McpSyncClient client : clients.getIfAvailable(List::of)) {
            connections.add(describe(client));
        }
        SyncMcpToolCallbackProvider provider = tools.getIfAvailable();
        int toolCount = (provider == null ? 0 : provider.getToolCallbacks().length) + runtime.callbacks().size();
        List<Map<String, Object>> dynamic = runtime.servers().stream().map(server -> Map.<String, Object>of(
                "name", server.name(), "status", server.status(), "initialized", "connected".equals(server.status()),
                "tools", server.tools(), "error", server.error() == null ? "" : server.error())).toList();
        connections.addAll(dynamic);
        return Map.of(
                "enabled", enabled,
                "dynamicManagement", true,
                "connectionCount", connections.size(),
                "toolCount", toolCount,
                "connections", connections);
    }

    @GetMapping("/api/mcp/servers")
    public List<McpRuntimeManager.ServerView> servers() {
        return runtime.servers();
    }

    @PostMapping("/api/mcp/servers")
    public McpRuntimeManager.ServerView create(@RequestBody McpRuntimeManager.ServerRequest request) {
        return runtime.save(request, null);
    }

    @PutMapping("/api/mcp/servers/{id}")
    public McpRuntimeManager.ServerView update(@PathVariable String id,
            @RequestBody McpRuntimeManager.ServerRequest request) {
        return runtime.save(request, id);
    }

    @DeleteMapping("/api/mcp/servers/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        if (!runtime.delete(id)) return Map.of("deleted", false, "error", "MCP server not found");
        return Map.of("deleted", true);
    }

    @PostMapping("/api/mcp/servers/{id}/test")
    public McpRuntimeManager.ServerView test(@PathVariable String id) {
        return runtime.test(id);
    }

    @PostMapping("/api/mcp/servers/{id}/call")
    public Object call(@PathVariable String id, @RequestBody McpCallRequest request) {
        return runtime.call(id, request.tool(), request.arguments());
    }

    @GetMapping("/api/mcp/servers/{id}/prompts")
    public List<McpRuntimeManager.PromptView> prompts(@PathVariable String id) {
        return runtime.prompts(id);
    }

    @GetMapping("/api/mcp/servers/{id}/resources")
    public List<McpRuntimeManager.ResourceView> resources(@PathVariable String id) {
        return runtime.resources(id);
    }

    public record McpCallRequest(String tool, Map<String, Object> arguments) {}

    private static Map<String, Object> describe(McpSyncClient client) {
        McpSchema.InitializeResult initialized = client.getCurrentInitializationResult();
        McpSchema.Implementation server = client.getServerInfo();
        return Map.of(
                "name", server == null || server.name() == null ? "unknown" : server.name(),
                "version", server == null || server.version() == null ? "" : server.version(),
                "protocolVersion", initialized == null || initialized.protocolVersion() == null
                        ? "" : initialized.protocolVersion(),
                "initialized", client.isInitialized());
    }
}
