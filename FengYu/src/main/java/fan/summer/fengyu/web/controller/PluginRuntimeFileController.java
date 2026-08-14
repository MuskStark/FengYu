package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.FilterOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/plugin-runtime/{id}/files")
public class PluginRuntimeFileController {
    static final int MAX_EXPORT_FILES = 2_000;
    static final int MAX_EXPORT_ENTRIES = 4_000;
    static final long MAX_EXPORT_BYTES = 500L * 1024 * 1024;

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
    public ResponseEntity<StreamingResponseBody> export(@PathVariable String id, @PathVariable String ref) throws IOException {
        require(id, "files.write");
        Path directory = files.resolve(id, ref);
        List<ExportFile> exportFiles = inspectExport(directory);
        StreamingResponseBody body = output -> writeZip(output, exportFiles);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/zip"))
            .header("Content-Disposition", "attachment; filename=plugin-output.zip")
            .body(body);
    }

    private static List<ExportFile> inspectExport(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Output reference is not a real directory");
        }
        Path root = directory.toRealPath();
        List<ExportFile> result = new ArrayList<>();
        long totalBytes = 0;
        try (var paths = Files.walk(directory)) {
            Iterator<Path> iterator = paths.iterator();
            int entries = 0;
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (path.equals(directory)) continue;
                if (++entries > MAX_EXPORT_ENTRIES) {
                    throw new IllegalArgumentException("Plugin output contains too many entries");
                }
                if (Files.isSymbolicLink(path)) {
                    throw new IllegalArgumentException("Plugin output must not contain symbolic links");
                }
                BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isDirectory()) continue;
                if (!attributes.isRegularFile()) {
                    throw new IllegalArgumentException("Plugin output contains an unsupported file type");
                }
                if (result.size() >= MAX_EXPORT_FILES) {
                    throw new IllegalArgumentException("Plugin output contains more than " + MAX_EXPORT_FILES + " files");
                }
                Path realPath = path.toRealPath();
                if (!realPath.startsWith(root)) {
                    throw new IllegalArgumentException("Plugin output file escapes the output directory");
                }
                totalBytes = Math.addExact(totalBytes, attributes.size());
                if (totalBytes > MAX_EXPORT_BYTES) {
                    throw new IllegalArgumentException("Plugin output exceeds 500 MB");
                }
                String entryName = directory.relativize(path).toString().replace('\\', '/');
                result.add(new ExportFile(entryName, realPath));
            }
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Plugin output size is invalid", e);
        }
        return List.copyOf(result);
    }

    private static void writeZip(OutputStream output, List<ExportFile> files) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new FilterOutputStream(output) {
                @Override public void close() throws IOException { flush(); }
            })) {
            byte[] buffer = new byte[16 * 1024];
            long totalBytes = 0;
            for (ExportFile file : files) {
                zip.putNextEntry(new ZipEntry(file.entryName()));
                try (InputStream input = Files.newInputStream(file.path(), LinkOption.NOFOLLOW_LINKS)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        totalBytes += count;
                        if (totalBytes > MAX_EXPORT_BYTES) {
                            throw new IOException("Plugin output grew beyond 500 MB while exporting");
                        }
                        zip.write(buffer, 0, count);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private record ExportFile(String entryName, Path path) {}

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
