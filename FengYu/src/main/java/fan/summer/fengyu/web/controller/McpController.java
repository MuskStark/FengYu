package fan.summer.fengyu.web.controller;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-only diagnostics for MCP connections configured through
 * {@code spring.ai.mcp.client.*}. Connection configuration remains startup-scoped so credentials
 * can stay in the launch environment or a protected external configuration file.
 */
@RestController
public class McpController {

    private final ObjectProvider<List<McpSyncClient>> clients;
    private final ObjectProvider<SyncMcpToolCallbackProvider> tools;
    private final boolean enabled;

    public McpController(
            @Qualifier("mcpSyncClients") ObjectProvider<List<McpSyncClient>> clients,
            ObjectProvider<SyncMcpToolCallbackProvider> tools,
            @Value("${spring.ai.mcp.client.enabled:true}") boolean enabled) {
        this.clients = clients;
        this.tools = tools;
        this.enabled = enabled;
    }

    @GetMapping("/api/mcp/status")
    public Map<String, Object> status() {
        List<Map<String, Object>> connections = new ArrayList<>();
        for (McpSyncClient client : clients.getIfAvailable(List::of)) {
            connections.add(describe(client));
        }
        SyncMcpToolCallbackProvider provider = tools.getIfAvailable();
        int toolCount = provider == null ? 0 : provider.getToolCallbacks().length;
        return Map.of(
                "enabled", enabled,
                "connectionCount", connections.size(),
                "toolCount", toolCount,
                "connections", connections);
    }

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
