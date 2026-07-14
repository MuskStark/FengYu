package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.runtime.InstalledPluginDescriptor;
import fan.summer.fengyu.plugin.runtime.PluginProcessManager;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
public class PluginRuntimeController {
    private final PluginPackageService packages;
    private final PluginProcessManager processes;

    public PluginRuntimeController(PluginPackageService packages, PluginProcessManager processes) {
        this.packages = packages;
        this.processes = processes;
    }

    @GetMapping("/api/plugin-runtime")
    public List<InstalledPluginDescriptor> plugins() {
        return packages.installed().stream().filter(m -> packages.isEnabled(m.id())).map(this::descriptor).toList();
    }

    @PostMapping("/api/plugin-runtime/{id}/invoke")
    public Object invoke(@PathVariable String id, @RequestBody InvokeRequest request) {
        return processes.invoke(id, request.method(), request.params());
    }

    @GetMapping("/plugin-runtime/{id}/**")
    public ResponseEntity<Resource> asset(@PathVariable String id, HttpServletRequest request) {
        PluginManifest manifest = packages.find(id).orElse(null);
        if (manifest == null || !packages.isEnabled(id)) return ResponseEntity.notFound().build();
        String full = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String prefix = "/plugin-runtime/" + id + "/";
        String relative = full.startsWith(prefix) ? full.substring(prefix.length()) : "";
        if (relative.isBlank()) relative = manifest.ui().entry();
        Path path = packages.asset(id, relative);
        if (!Files.isRegularFile(path)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
            .contentType(contentType(path.getFileName().toString()))
            .header("Access-Control-Allow-Origin", "*")
            .header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'none'; object-src 'none'; base-uri 'none'")
            .header("X-Content-Type-Options", "nosniff")
            .body(new FileSystemResource(path));
    }

    private InstalledPluginDescriptor descriptor(PluginManifest m) {
        return new InstalledPluginDescriptor(m.id(), m.name(), m.description(),
            m.category() == null ? "OTHER" : m.category().toUpperCase(), m.icon(), m.version(),
            "/plugin-runtime/" + m.id() + "/" + m.ui().entry(), m.author(),
            m.permissions() == null ? List.of() : m.permissions(), packages.isEnabled(m.id()),
            "BLUE", m.aiTools() != null && !m.aiTools().isEmpty(), m.official() ? "OFFICIAL" : "THIRD_PARTY");
    }

    static MediaType contentType(String name) {
        if (name.endsWith(".html")) return utf8("text", "html");
        if (name.endsWith(".js") || name.endsWith(".mjs")) return utf8("text", "javascript");
        if (name.endsWith(".css")) return utf8("text", "css");
        if (name.endsWith(".json")) return utf8("application", "json");
        if (name.endsWith(".svg")) return MediaType.parseMediaType("image/svg+xml");
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private static MediaType utf8(String type, String subtype) {
        return new MediaType(type, subtype, StandardCharsets.UTF_8);
    }

    public record InvokeRequest(String method, Map<String, Object> params) {}
}
