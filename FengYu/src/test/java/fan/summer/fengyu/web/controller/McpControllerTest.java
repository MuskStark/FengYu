package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.mcp.McpRuntimeManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REST-shape regression: {@code POST /api/mcp/servers} creates a resource, so it answers
 * 201 Created like the other creating endpoints (plugin packages, notifications, scopes).
 */
class McpControllerTest {

    private McpController controller(McpRuntimeManager runtime) {
        @SuppressWarnings("unchecked")
        ObjectProvider<List<io.modelcontextprotocol.client.McpSyncClient>> clients =
                mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SyncMcpToolCallbackProvider> tools = mock(ObjectProvider.class);
        return new McpController(clients, tools, runtime, true);
    }

    @Test
    void createAnswersCreatedAndForwardsTheSavedView() {
        McpRuntimeManager runtime = mock(McpRuntimeManager.class);
        McpRuntimeManager.ServerRequest request = new McpRuntimeManager.ServerRequest(
                "refs", "stdio", "uvx", List.of(), null, null, null, null, null);
        McpRuntimeManager.ServerView saved = new McpRuntimeManager.ServerView(
                "refs", "refs", "stdio", "uvx", List.of(), null, null, true,
                "stopped", null, null, null, List.of(), List.of(), List.of(), List.of(),
                30, 30, "manual", null);
        when(runtime.save(same(request), isNull())).thenReturn(saved);

        ResponseEntity<McpRuntimeManager.ServerView> response =
                controller(runtime).create(request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                "resource creation must answer 201, not a bare 200");
        assertEquals("refs", response.getBody().id());
    }
}
