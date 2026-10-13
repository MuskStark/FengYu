package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.mcp.apps.McpSandboxRegistry;
import fan.summer.fengyu.ai.mcp.apps.McpToolUiMeta;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves MCP Apps sandbox pages on the sandbox origin (127.0.0.1 at the dedicated sandbox
 * port — see {@code SandboxConnectorConfig}): {@code GET /sandbox/app/{sandboxId}} returns
 * the registered third-party HTML document, with its CSP built strictly from the resource's
 * declared domains ({@code default-src 'none'}, plus the {@code _meta.ui.csp} allowlists)
 * and framing pinned to the FengYu host origins.
 *
 * <p>The sandboxId is a random 128-bit capability minted by {@code /api/mcp/ui/prepare};
 * this endpoint is token-free (like {@code /plugin-runtime/*}) because a sandboxed iframe
 * navigation cannot attach headers — the loopback Host firewall plus the unguessable id are
 * the gate, and {@code SandboxOriginFilter} guarantees this content is unreachable from the
 * app origin, so third-party MCP HTML never executes where the auth token lives. The iframe
 * itself carries the {@code sandbox} attribute (no {@code allow-same-origin}), so the page
 * runs under an opaque origin: full script isolation from the host, no persistent storage.
 */
@RestController
public class McpSandboxController {

    private final McpSandboxRegistry sandboxes;

    public McpSandboxController(McpSandboxRegistry sandboxes) {
        this.sandboxes = sandboxes;
    }

    @GetMapping("/sandbox/app/{sandboxId}")
    public ResponseEntity<String> app(@PathVariable String sandboxId) {
        McpSandboxRegistry.SandboxEntry entry = sandboxes.get(sandboxId);
        if (entry == null) return notFound();
        McpToolUiMeta.McpUiCsp csp = entry.resourceMeta() != null
                ? McpToolUiMeta.effectiveCsp(entry.resourceMeta().csp(), entry.csp())
                : entry.csp();
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("Content-Security-Policy",
                        McpToolUiMeta.buildAppPageCsp(csp, McpToolUiMeta.HOST_FRAME_ANCESTORS))
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(entry.html());
    }

    private ResponseEntity<String> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_PLAIN)
                .body("not found");
    }
}
