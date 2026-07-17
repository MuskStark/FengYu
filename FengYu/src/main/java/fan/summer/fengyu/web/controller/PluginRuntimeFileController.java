package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/plugin-runtime/{id}/files")
public class PluginRuntimeFileController {
    private final PluginPackageService packages;
    private final PluginFileGrantService files;

    public PluginRuntimeFileController(PluginPackageService packages, PluginFileGrantService files) {
        this.packages = packages; this.files = files;
    }

    @PostMapping("/upload")
    public ResponseEntity<PluginFileGrantService.FileRef> upload(@PathVariable String id,
            @RequestPart("file") MultipartFile file) throws IOException {
        require(id, "files.read");
        return ResponseEntity.status(HttpStatus.CREATED).body(files.upload(id, file));
    }

    @PostMapping("/upload-directory")
    public ResponseEntity<PluginFileGrantService.FileRef> uploadDirectory(@PathVariable String id,
            @RequestPart("files") List<MultipartFile> uploads,
            @RequestParam("paths") List<String> paths,
            @RequestParam(value = "access", defaultValue = "read") String access) throws IOException {
        requireAccess(id, access);
        return ResponseEntity.status(HttpStatus.CREATED).body(files.uploadDirectory(id, uploads, paths, access));
    }

    @PostMapping("/native")
    public PluginFileGrantService.FileRef nativeGrant(@PathVariable String id, @RequestBody NativeGrant request) throws IOException {
        requireAccess(id, request.access());
        return files.grantNative(id, request.path(), request.kind(), request.access());
    }

    @PostMapping("/output")
    public PluginFileGrantService.FileRef output(@PathVariable String id) throws IOException {
        require(id, "files.write");
        return files.outputDirectory(id);
    }

    @GetMapping("/export/{ref}")
    public ResponseEntity<ByteArrayResource> export(@PathVariable String id, @PathVariable String ref) throws IOException {
        require(id, "files.write");
        Path directory = files.resolve(id, ref);
        if (!Files.isDirectory(directory)) throw new IllegalArgumentException("Output reference is not a directory");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes); var paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                zip.putNextEntry(new ZipEntry(directory.relativize(path).toString().replace('\\', '/')));
                Files.copy(path, zip); zip.closeEntry();
            }
        }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/zip"))
            .header("Content-Disposition", "attachment; filename=plugin-output.zip")
            .body(new ByteArrayResource(bytes.toByteArray()));
    }

    private void require(String id, String permission) {
        var manifest = packages.find(id).orElseThrow(() -> new IllegalArgumentException("Plugin is not installed"));
        if (manifest.permissions() == null || !manifest.permissions().contains(permission)) {
            throw new IllegalArgumentException("Plugin lacks permission: " + permission);
        }
    }

    private void requireAccess(String id, String access) {
        switch (access) {
            case "read" -> require(id, "files.read");
            case "write" -> require(id, "files.write");
            case "read-write" -> {
                require(id, "files.read");
                require(id, "files.write");
            }
            default -> throw new IllegalArgumentException("Invalid file access: " + access);
        }
    }

    public record NativeGrant(String path, String kind, String access) {}
}
