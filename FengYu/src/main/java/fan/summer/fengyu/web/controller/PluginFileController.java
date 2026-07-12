package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.plugin.PluginRegistryService;
import fan.summer.fengyu.plugin.workspace.PluginWorkspaceService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Generic plugin file I/O (FengYu Plugin File I/O Standard v1): multipart upload into a
 *  session workspace, zip download of results, and session cleanup. Plugin-agnostic. */
@RestController
public class PluginFileController {

    private static final long MAX_BYTES = 100L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXT = Set.of(".xlsx", ".xls");

    private final PluginWorkspaceService workspace;
    private final PluginRegistryService registry;

    public PluginFileController(PluginWorkspaceService workspace, PluginRegistryService registry) {
        this.workspace = workspace;
        this.registry = registry;
    }

    @PostMapping("/api/plugins/{id}/files")
    public ResponseEntity<Object> upload(@PathVariable String id,
                                         @RequestParam(value = "session", required = false) String session,
                                         @RequestParam("file") MultipartFile file) throws IOException {
        if (registry.find(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("success", false, "error", "Unknown plugin id: " + id));
        }
        String name = file.getOriginalFilename();
        if (name == null || !hasAllowedExt(name)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Only .xlsx/.xls allowed"));
        }
        if (file.getSize() > MAX_BYTES) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "File exceeds 100MB"));
        }
        String sess = (session == null || session.isBlank()) ? workspace.newSession() : session;
        Path stored = workspace.store(id, sess, name, file.getInputStream());
        Map<String, Object> fileInfo = new LinkedHashMap<>();
        fileInfo.put("name", name);
        fileInfo.put("path", stored.toAbsolutePath().toString());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session", sess);
        out.put("files", List.of(fileInfo));
        return ResponseEntity.ok(out);
    }

    @GetMapping("/api/plugins/{id}/files/archive")
    public ResponseEntity<StreamingResponseBody> archive(@PathVariable String id,
                                                          @RequestParam String session,
                                                          @RequestParam(defaultValue = "out") String dir) {
        if (registry.find(id).isEmpty()) return ResponseEntity.notFound().build();
        if (!dir.equals("out") && !dir.equals("in")) return ResponseEntity.badRequest().build();
        Path target = dir.equals("out") ? workspace.outDir(id, session) : workspace.inDir(id, session);
        StreamingResponseBody body = os -> workspace.zipDir(target, os);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/zip"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"results.zip\"")
            .body(body);
    }

    @DeleteMapping("/api/plugins/{id}/files")
    public ResponseEntity<Object> delete(@PathVariable String id, @RequestParam String session) {
        workspace.remove(id, session);
        return ResponseEntity.ok(Map.of("success", true));
    }

    private static boolean hasAllowedExt(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return ALLOWED_EXT.contains(name.substring(dot).toLowerCase(Locale.ROOT));
    }
}
