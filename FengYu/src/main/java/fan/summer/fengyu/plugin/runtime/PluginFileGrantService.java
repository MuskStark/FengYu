package fan.summer.fengyu.plugin.runtime;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-process opaque file grants shared by Web upload and trusted desktop selection adapters. */
@Service
public class PluginFileGrantService {
    private final Path root = Path.of(System.getProperty("java.io.tmpdir"), "fengyu", "runtime-files");
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();

    public FileRef upload(String pluginId, MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("File is empty");
        Path dir = Files.createDirectories(root.resolve(pluginId).resolve(UUID.randomUUID().toString()));
        String name = Path.of(file.getOriginalFilename() == null ? "file" : file.getOriginalFilename()).getFileName().toString();
        Path target = dir.resolve(name);
        try (var in = file.getInputStream()) { Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING); }
        return register(pluginId, target, "file", "read");
    }

    public FileRef grantNative(String pluginId, String rawPath, String kind, String access) throws IOException {
        Path path = Path.of(rawPath).toRealPath();
        if ("directory".equals(kind) != Files.isDirectory(path)) throw new IllegalArgumentException("Selected path kind does not match");
        return register(pluginId, path, kind, access);
    }

    public FileRef outputDirectory(String pluginId) throws IOException {
        Path dir = Files.createDirectories(root.resolve(pluginId).resolve(UUID.randomUUID().toString()).resolve("out"));
        return register(pluginId, dir, "directory", "write");
    }

    public Path resolve(String pluginId, String id) {
        Grant grant = grants.get(id);
        if (grant == null || !grant.pluginId.equals(pluginId)) throw new IllegalArgumentException("Unknown or unauthorized file reference");
        return grant.path;
    }

    private FileRef register(String pluginId, Path path, String kind, String access) throws IOException {
        String id = "ref_" + UUID.randomUUID();
        grants.put(id, new Grant(pluginId, path, kind, access));
        long size = Files.isRegularFile(path) ? Files.size(path) : 0;
        return new FileRef(id, path.getFileName().toString(), kind, access, size);
    }

    @PreDestroy void close() throws IOException {
        grants.clear();
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    public record FileRef(String id, String name, String kind, String access, long size) {}
    private record Grant(String pluginId, Path path, String kind, String access) {}
}
