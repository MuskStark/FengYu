package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.mcp.McpRuntimeManager;
import fan.summer.fengyu.ai.mcp.apps.McpSandboxRegistry;
import fan.summer.fengyu.ai.mcp.apps.McpToolUiMeta;
import fan.summer.fengyu.ai.service.McpSamplingConfig;
import fan.summer.fengyu.web.StreamTicketService;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The MCP Apps host API: everything the browser-side host renderer needs to prepare, serve,
 * and drive MCP Apps sandbox pages — {@code ui://} HTML preparation, page-initiated resource
 * reads and tool calls (each behind explicit user confirmation in the web UI), resource
 * subscriptions, the browser event stream, and page sampling (when enabled). Ownership checks
 * are server-scoped: a page can only ever touch the server its card belongs to.
 */
@RestController
public class McpAppsController {

    private final McpRuntimeManager runtime;
    private final McpSandboxRegistry sandboxes;
    private final McpUiEventHub hub;
    private final StreamTicketService tickets;
    private final fan.summer.fengyu.web.SandboxOrigin sandboxOrigin;

    public McpAppsController(McpRuntimeManager runtime, McpSandboxRegistry sandboxes,
            McpUiEventHub hub, StreamTicketService tickets,
            fan.summer.fengyu.web.SandboxOrigin sandboxOrigin) {
        this.runtime = runtime;
        this.sandboxes = sandboxes;
        this.hub = hub;
        this.tickets = tickets;
        this.sandboxOrigin = sandboxOrigin;
    }

    // ── page lifecycle ─────────────────────────────────────────────────────────

    /**
     * Prepares one sandbox page: fetches the {@code ui://} HTML through the owning server,
     * registers it under a fresh capability id, and returns everything the host renderer needs
     * (shell URL, effective CSP for the host-side permission model, size hints, display hints).
     */
    @PostMapping("/api/mcp/ui/prepare")
    public Map<String, Object> prepare(@RequestBody PrepareRequest request) {
        String serverId = required(request.serverId(), "serverId");
        String resourceUri = required(request.resourceUri(), "resourceUri");
        McpRuntimeManager.UiHtmlResource resource = runtime.readUiHtmlResource(serverId, resourceUri);
        McpToolUiMeta.McpToolUiDescriptor descriptor = findDescriptor(serverId, resourceUri);
        McpToolUiMeta.McpUiCsp csp = McpToolUiMeta.effectiveCsp(
                resource.meta() == null ? null : resource.meta().csp(),
                descriptor == null ? null : descriptor.csp());
        String serverName = serverName(serverId);
        McpSandboxRegistry.SandboxEntry entry = sandboxes.register(serverId, serverName, resourceUri,
                request.scopeId() == null ? "" : request.scopeId(), resource.html(), csp, resource.meta());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sandboxId", entry.sandboxId());
        out.put("serverName", serverName);
        out.put("resourceUri", resourceUri);
        if (csp != null) out.put("csp", cspMap(csp));
        if (resource.meta() != null) {
            McpToolUiMeta.McpAppResourceMeta meta = resource.meta();
            Map<String, Object> hints = new LinkedHashMap<>();
            if (meta.prefersBorder() != null) hints.put("prefersBorder", meta.prefersBorder());
            if (meta.heightHint() != null) hints.put("heightHint", meta.heightHint());
            if (meta.minFrameHeight() != null) hints.put("minFrameHeight", meta.minFrameHeight());
            if (meta.showInline() != null) hints.put("showInline", meta.showInline());
            if (!meta.permissions().isEmpty()) hints.put("permissions", meta.permissions());
            if (!hints.isEmpty()) out.put("resourceMeta", hints);
        }
        if (descriptor != null) {
            if (descriptor.preferredDisplayMode() != null) {
                out.put("preferredDisplayMode", descriptor.preferredDisplayMode());
            }
            if (descriptor.showInline() != null) out.put("showInline", descriptor.showInline());
            if (descriptor.surface() != null) out.put("surface", descriptor.surface());
        }
        out.put("samplingEnabled", McpRuntimeManager.samplingEnabled());
        out.put("sandboxUrl", sandboxOrigin.active() ? sandboxOrigin.appUrl(entry.sandboxId()) : null);
        return out;
    }

    /** Explicit teardown when the hosting card unmounts (the TTL sweep is the backstop). */
    @PostMapping("/api/mcp/ui/dispose")
    public Map<String, Object> dispose(@RequestBody DisposeRequest request) {
        boolean removed = sandboxes.dispose(request.sandboxId());
        return Map.of("disposed", removed);
    }

    // ── page-initiated reads and calls ─────────────────────────────────────────

    /** Page-initiated {@code resources/read}: MIME allowlist + 8 MiB decoded cap. */
    @PostMapping("/api/mcp/ui/read")
    public Map<String, Object> read(@RequestBody ReadRequest request) {
        List<McpRuntimeManager.UiResourceContentsView> contents =
                runtime.readResourceForUi(required(request.serverId(), "serverId"),
                        required(request.uri(), "uri"));
        return Map.of("contents", contents);
    }

    @GetMapping("/api/mcp/ui/resources")
    public Map<String, Object> resources(@RequestParam String serverId,
            @RequestParam(required = false) String cursor) {
        McpRuntimeManager.ResourceListPage page = runtime.resourcesPage(serverId, cursor);
        return Map.of("resources", page.resources() == null ? List.of() : page.resources(),
                "nextCursor", page.nextCursor() == null ? "" : page.nextCursor());
    }

    @GetMapping("/api/mcp/ui/resource-templates")
    public Map<String, Object> resourceTemplates(@RequestParam String serverId) {
        return Map.of("resourceTemplates", runtime.resourceTemplates(serverId));
    }

    /** UI-capable tools of one server — the Settings surface uses it for the panel preview. */
    @GetMapping("/api/mcp/ui/tools")
    public Map<String, Object> uiTools(@RequestParam String serverId) {
        return Map.of("tools", runtime.uiToolViews(serverId));
    }

    /**
     * Page-initiated {@code tools/call}. The WEB UI confirms every call before it lands here
     * (host-enforced consent, mirroring the agent's approval-first rule); the backend adds the
     * structural guarantee that the call runs against exactly the named server.
     */
    @PostMapping("/api/mcp/ui/call")
    public Map<String, Object> call(@RequestBody UiCallRequest request) {
        return runtime.callUiTool(required(request.serverId(), "serverId"),
                required(request.tool(), "tool"), request.arguments());
    }

    // ── resource subscriptions ─────────────────────────────────────────────────

    @PostMapping("/api/mcp/ui/subscribe")
    public Map<String, Object> subscribe(@RequestBody SubscriptionRequest request) {
        String serverId = required(request.serverId(), "serverId");
        String uri = required(request.uri(), "uri");
        hub.acquireSubscription(serverId, uri);
        return Map.of("subscribed", true);
    }

    @PostMapping("/api/mcp/ui/unsubscribe")
    public Map<String, Object> unsubscribe(@RequestBody SubscriptionRequest request) {
        hub.releaseSubscription(required(request.serverId(), "serverId"),
                required(request.uri(), "uri"));
        return Map.of("subscribed", false);
    }

    // ── browser event stream ───────────────────────────────────────────────────

    /** One-time SSE ticket, same mechanism as the chat stream (EventSource cannot send headers). */
    @PostMapping("/api/mcp/ui/stream-ticket")
    public Map<String, Object> streamTicket() {
        StreamTicketService.IssuedTicket issued =
                tickets.issue(StreamTicketService.MCP_UI_EVENTS_ENDPOINT);
        return Map.of("ticket", issued.ticket(), "expiresAt", issued.expiresAt().toString());
    }

    @GetMapping(value = "/api/mcp/ui/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@RequestParam("ticket") String ticket) {
        // The ticket was already redeemed by TokenAuthFilter; reaching here means it was valid.
        return hub.register();
    }

    // ── page sampling (opt-in) ─────────────────────────────────────────────────

    /**
     * Forwards one page sampling request to the active backend; dark unless opted in.
     * Carries the page's tool loop (`sampling.tools`): tools/toolChoice ride along, and
     * a {@code stopReason:"toolUse"} reply returns the {@code toolCalls} the PAGE runs.
     */
    @PostMapping("/api/mcp/ui/sampling")
    public Map<String, Object> sampling(@RequestBody SamplingRequest request) {
        if (!McpRuntimeManager.samplingEnabled()) {
            throw new IllegalArgumentException("Sampling is disabled");
        }
        List<McpSamplingConfig.UiLoopMessage> messages = new java.util.ArrayList<>();
        if (request.messages() != null) {
            for (SamplingMessageDto dto : request.messages()) {
                if (dto == null || dto.blocks() == null || dto.blocks().isEmpty()) continue;
                List<McpSamplingConfig.UiLoopBlock> blocks = new java.util.ArrayList<>();
                for (SamplingBlockDto block : dto.blocks()) {
                    if (block == null) continue;
                    blocks.add(new McpSamplingConfig.UiLoopBlock(block.kind(), block.text(),
                            block.id(), block.name(), block.input(), block.toolUseId(), block.isError()));
                }
                messages.add(new McpSamplingConfig.UiLoopMessage(
                        text(dto.role()), List.copyOf(blocks)));
            }
        }
        List<McpSamplingConfig.UiLoopTool> tools = new java.util.ArrayList<>();
        if (request.tools() != null) {
            for (SamplingToolDto tool : request.tools()) {
                if (tool == null) continue;
                tools.add(new McpSamplingConfig.UiLoopTool(tool.name(), tool.description(), tool.inputSchema()));
            }
        }
        McpSamplingConfig.UiLoopResult result = runtime.sampleLoopForUi(new McpSamplingConfig.UiLoopRequest(
                List.copyOf(messages), request.systemPrompt(), request.maxTokens(),
                request.temperature(), List.copyOf(tools), request.toolChoice()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", result.text() == null ? "" : result.text());
        out.put("model", result.model() == null ? "" : result.model());
        out.put("stopReason", result.stopReason());
        out.put("toolCalls", result.toolCalls() == null ? List.of() : result.toolCalls());
        return out;
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private McpToolUiMeta.McpToolUiDescriptor findDescriptor(String serverId, String resourceUri) {
        for (McpToolUiMeta.UiToolInfo info : runtime.uiTools(serverId)) {
            if (info.descriptor().resourceUri().equals(resourceUri)) return info.descriptor();
        }
        return null;
    }

    private String serverName(String serverId) {
        return runtime.servers().stream()
                .filter(server -> server.id().equals(serverId))
                .findFirst()
                .map(McpRuntimeManager.ServerView::name)
                .orElse("");
    }

    private static Map<String, Object> cspMap(McpToolUiMeta.McpUiCsp csp) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (csp.connectDomains() != null) out.put("connectDomains", csp.connectDomains());
        if (csp.resourceDomains() != null) out.put("resourceDomains", csp.resourceDomains());
        if (csp.frameDomains() != null) out.put("frameDomains", csp.frameDomains());
        if (csp.baseUriDomains() != null) out.put("baseUriDomains", csp.baseUriDomains());
        return out;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String text(Object value) {
        return value instanceof String s ? s : "";
    }

    public record PrepareRequest(String serverId, String resourceUri, String scopeId) {}
    public record DisposeRequest(String sandboxId) {}
    public record ReadRequest(String serverId, String uri) {}
    public record UiCallRequest(String serverId, String tool, Map<String, Object> arguments) {}
    public record SubscriptionRequest(String serverId, String uri) {}
    /** One sampling content block: text | tool_use | tool_result (page tool loop). */
    public record SamplingBlockDto(String kind, String text, String id, String name,
            Map<String, Object> input, String toolUseId, Boolean isError) {}
    public record SamplingMessageDto(String role, List<SamplingBlockDto> blocks) {}
    public record SamplingToolDto(String name, String description, Map<String, Object> inputSchema) {}
    public record SamplingRequest(List<SamplingMessageDto> messages, String systemPrompt,
            Integer maxTokens, Double temperature, List<SamplingToolDto> tools, String toolChoice) {}
}
