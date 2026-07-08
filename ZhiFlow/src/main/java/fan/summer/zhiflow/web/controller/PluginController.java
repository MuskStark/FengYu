package fan.summer.zhiflow.web.controller;

import fan.summer.zhiflow.api.plugin.PluginDescriptor;
import fan.summer.zhiflow.plugin.PluginRegistryService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Map;

/**
 * Plugin endpoints:
 * <ul>
 *   <li>{@code GET  /api/plugins} — list registered plugin descriptors.</li>
 *   <li>{@code POST /api/plugins/{id}/invoke} — {@code {action, args}} JSON-in / JSON-out.</li>
 *   <li>{@code GET  /plugin-ui/{id}/**} — serve a plugin's built micro-frontend ESM bundle
 *       from its classpath resources ({@code /ui/{id}/...}).</li>
 * </ul>
 */
@RestController
public class PluginController {

    private final PluginRegistryService registry;

    public PluginController(PluginRegistryService registry) {
        this.registry = registry;
    }

    @GetMapping("/api/plugins")
    public List<PluginDescriptor> list() {
        return registry.descriptors();
    }

    @PostMapping("/api/plugins/{id}/invoke")
    public ResponseEntity<Object> invoke(@PathVariable String id, @RequestBody InvokeRequest req) {
        if (registry.find(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("success", false, "error", "Unknown plugin id: " + id));
        }
        try {
            Object result = registry.invoke(id, req.action(),
                req.args() != null ? req.args() : Map.of());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body(Map.of("success", false, "error", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "error", String.valueOf(e.getMessage())));
        }
    }

    /**
     * Serves the plugin micro-frontend bundle. The wildcard path after {@code /plugin-ui/{id}/}
     * maps to a classpath resource under {@code /ui/{id}/}. Bundles are packaged into the plugin
     * module's {@code src/main/resources/ui/{id}/} and merged onto the app classpath at build time.
     */
    @GetMapping("/plugin-ui/{id}/**")
    public ResponseEntity<Resource> pluginUi(@PathVariable String id, HttpServletRequest request) {
        String fullPath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String prefix = "/plugin-ui/" + id + "/";
        String rel = fullPath.startsWith(prefix) ? fullPath.substring(prefix.length()) : "";
        if (rel.isBlank()) rel = "index.js";
        if (rel.contains("..")) {
            return ResponseEntity.badRequest().build();
        }
        ClassPathResource resource = new ClassPathResource("ui/" + id + "/" + rel);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
            .contentType(contentTypeFor(rel))
            .body(resource);
    }

    private static MediaType contentTypeFor(String path) {
        if (path.endsWith(".js") || path.endsWith(".mjs")) {
            return MediaType.parseMediaType("text/javascript");
        }
        if (path.endsWith(".css")) return MediaType.parseMediaType("text/css");
        if (path.endsWith(".html")) return MediaType.TEXT_HTML;
        if (path.endsWith(".json")) return MediaType.APPLICATION_JSON;
        if (path.endsWith(".svg")) return MediaType.parseMediaType("image/svg+xml");
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    /** Request body for {@code POST /api/plugins/{id}/invoke}. */
    public record InvokeRequest(String action, Map<String, Object> args) {}
}
