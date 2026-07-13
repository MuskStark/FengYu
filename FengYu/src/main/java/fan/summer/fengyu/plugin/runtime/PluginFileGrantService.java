package fan.summer.fengyu.plugin.runtime;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-process opaque file grants shared by Web upload and trusted desktop selection adapters. */
@Service
public class PluginFileGrantService {
    private final Path root;
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();

    public PluginFileGrantService() {
        this(Path.of(System.getProperty("java.io.tmpdir"), "fengyu", "runtime-files"));
    }

    PluginFileGrantService(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public FileRef upload(String pluginId, MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("File is empty");
        Path dir = Files.createDirectories(root.resolve(pluginId).resolve(UUID.randomUUID().toString()));
        String name = Path.of(file.getOriginalFilename() == null ? "file" : file.getOriginalFilename()).getFileName().toString();
        Path target = dir.resolve(name);
        try (var in = file.getInputStream()) { Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING); }
        return register(pluginId, target, "file", "read");
    }

    public FileRef uploadDirectory(String pluginId, List<MultipartFile> files,
            List<String> relativePaths) throws IOException {
        if (files == null || files.isEmpty()) throw new IllegalArgumentException("Directory is empty");
        if (relativePaths == null || files.size() != relativePaths.size()) {
            throw new IllegalArgumentException("Each uploaded file requires one relative path");
        }
        Path directory = Files.createDirectories(root.resolve(pluginId).resolve(UUID.randomUUID().toString()));
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            if (file.isEmpty()) throw new IllegalArgumentException("Directory contains an empty file");
            String raw = relativePaths.get(i);
            if (raw == null || raw.isBlank() || Path.of(raw).isAbsolute()) {
                throw new IllegalArgumentException("Invalid directory entry path");
            }
            Path target = directory.resolve(raw).normalize();
            if (!target.startsWith(directory) || target.equals(directory)) {
                throw new IllegalArgumentException("Directory entry escapes the upload root");
            }
            Files.createDirectories(target.getParent());
            try (var in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return register(pluginId, directory, "directory", "read");
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
