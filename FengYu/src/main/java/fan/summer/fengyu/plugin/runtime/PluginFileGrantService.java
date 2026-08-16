package fan.summer.fengyu.plugin.runtime;

import fan.summer.fengyu.runtime.RuntimePaths;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.concurrent.atomic.AtomicLong;

/** Per-process opaque file grants shared by Web upload and trusted desktop selection adapters. */
@Service
public class PluginFileGrantService {
    private static final long MAX_SINGLE_FILE_BYTES = 100L * 1024 * 1024;
    private static final long MAX_GRANT_BYTES = 500L * 1024 * 1024;
    private static final int MAX_DIRECTORY_FILES = 2_000;
    private static final int MAX_ACTIVE_GRANTS = 1_000;
    private final Path root;
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> versions = new ConcurrentHashMap<>();

    public PluginFileGrantService() {
        this("");
    }

    @Autowired
    public PluginFileGrantService(@Value("${fengyu.runtime-files.directory:}") String directory) {
        this(directory == null || directory.isBlank()
                ? RuntimePaths.runtimeFilesDirectory(RuntimePaths.root())
                : Path.of(directory));
    }

    PluginFileGrantService(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public FileRef upload(String pluginId, MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("File is empty");
        if (file.getSize() > MAX_SINGLE_FILE_BYTES) throw new IllegalArgumentException("File exceeds 100 MB");
        Path dir = Files.createDirectories(root.resolve(pluginId).resolve(UUID.randomUUID().toString()));
        String name = Path.of(file.getOriginalFilename() == null ? "file" : file.getOriginalFilename()).getFileName().toString();
        Path target = dir.resolve(name);
        try (var in = file.getInputStream()) { Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING); }
        return register(pluginId, target, "file", "read", true);
    }

    public FileRef uploadDirectory(String pluginId, List<MultipartFile> files,
            List<String> relativePaths) throws IOException {
        return uploadDirectory(pluginId, files, relativePaths, "read");
    }

    public FileRef uploadDirectory(String pluginId, List<MultipartFile> files,
            List<String> relativePaths, String access) throws IOException {
        if (!List.of("read", "read-write").contains(access)) {
            throw new IllegalArgumentException("Uploaded directories require read or read-write access");
        }
        if (files == null || files.isEmpty()) throw new IllegalArgumentException("Directory is empty");
        if (files.size() > MAX_DIRECTORY_FILES) throw new IllegalArgumentException("Directory contains too many files");
        long totalBytes = files.stream().mapToLong(MultipartFile::getSize).sum();
        if (totalBytes > MAX_GRANT_BYTES) throw new IllegalArgumentException("Directory exceeds 500 MB");
        if (files.stream().anyMatch(file -> file.getSize() > MAX_SINGLE_FILE_BYTES)) {
            throw new IllegalArgumentException("Directory contains a file larger than 100 MB");
        }
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
        return register(pluginId, directory, "directory", access, true);
    }

    public FileRef grantNative(String pluginId, String rawPath, String kind, String access) throws IOException {
        Path path = Path.of(rawPath).toRealPath();
        if (!List.of("file", "directory").contains(kind)
                || !List.of("read", "write", "read-write").contains(access)) {
            throw new IllegalArgumentException("Invalid native file grant");
        }
        if ("directory".equals(kind) != Files.isDirectory(path)) throw new IllegalArgumentException("Selected path kind does not match");
        enforceNativeQuota(path);
        Path granted = "read".equals(access) ? snapshot(pluginId, path) : path;
        return register(pluginId, granted, kind, access, "read".equals(access));
    }

    public FileRef outputDirectory(String pluginId) throws IOException {
        Path dir = Files.createDirectories(root.resolve(pluginId).resolve(UUID.randomUUID().toString()).resolve("out"));
        return register(pluginId, dir, "directory", "write", true);
    }

    /** Name of the host-owned cross-plugin scratch root under {@link #root}. */
    private static final String SHARED_DIRECTORY_NAME = "_shared";

    /**
     * Creates an empty host-owned scratch directory for cross-plugin workflow hand-offs (e.g. an
     * Excel split step whose outputs a later Email step reads). Lives under the runtime-files
     * root; the LAST revoke of a grant pointing here deletes the tree (see {@link #revoke}).
     */
    public Path createSharedDirectory() throws IOException {
        return Files.createDirectories(root.resolve(SHARED_DIRECTORY_NAME).resolve(UUID.randomUUID().toString()));
    }

    /**
     * Grants an existing path LIVE — no read snapshot — for host-created shared scratch dirs.
     * Unlike a {@code read} {@link #grantNative}, later writes by one grantee stay visible to the
     * others, which is exactly the hand-off semantics a multi-step workflow needs.
     */
    public FileRef grantLive(String pluginId, Path path, String kind, String access) throws IOException {
        Path real = path.toRealPath();
        if (!List.of("file", "directory").contains(kind)
                || !List.of("read", "read-write").contains(access)) {
            throw new IllegalArgumentException("Invalid live file grant");
        }
        if ("directory".equals(kind) != Files.isDirectory(real)) {
            throw new IllegalArgumentException("Selected path kind does not match");
        }
        return register(pluginId, real, kind, access, false);
    }

    public Path resolve(String pluginId, String id) {
        Grant grant = grants.get(id);
        if (grant == null || !grant.pluginId.equals(pluginId)) throw new IllegalArgumentException("Unknown or unauthorized file reference");
        return grant.path;
    }

    public void validate(String pluginId, FileRef ref) {
        if (ref == null) throw new IllegalArgumentException("Missing file reference");
        Grant grant = grants.get(ref.id());
        if (grant == null || !grant.pluginId.equals(pluginId)
                || !grant.kind.equals(ref.kind()) || !grant.access.equals(ref.access())) {
            throw new IllegalArgumentException("Unknown, unauthorized, or altered file reference");
        }
    }

    public List<Path> writablePaths(String pluginId) {
        return grants.values().stream()
                .filter(grant -> grant.pluginId.equals(pluginId))
                .filter(grant -> List.of("write", "read-write").contains(grant.access))
                .map(Grant::path).distinct().toList();
    }

    /** Paths granted to a plugin for reading, including read-only uploads and native snapshots. */
    public List<Path> readablePaths(String pluginId) {
        return grants.values().stream()
                .filter(grant -> grant.pluginId.equals(pluginId))
                .map(Grant::path).distinct().toList();
    }

    public long grantVersion(String pluginId) {
        AtomicLong version = versions.get(pluginId);
        return version == null ? 0 : version.get();
    }

    public void revoke(String pluginId, String id) {
        Grant grant = grants.get(id);
        if (grant == null || !grant.pluginId.equals(pluginId) || !grants.remove(id, grant)) return;
        versions.computeIfAbsent(pluginId, ignored -> new AtomicLong()).incrementAndGet();
        if (grant.owned) {
            deleteTree(ownedGrantRoot(grant.path));
        } else if (isSharedScratch(grant.path) && lastGrantFor(grant.path)) {
            // The final live grant for a host-created cross-plugin scratch directory is gone —
            // reclaim the directory itself. grantLive grants are not owned (nothing else would
            // ever delete them), and a native writable path is never under `_shared`, so this
            // branch only ever reclaims what createSharedDirectory produced.
            deleteTree(grant.path);
        }
    }

    /** True for paths inside the host-owned `_shared` scratch root (see {@link #createSharedDirectory()}). */
    private boolean isSharedScratch(Path path) {
        try {
            return path.toAbsolutePath().normalize()
                    .startsWith(root.resolve(SHARED_DIRECTORY_NAME).toRealPath());
        } catch (IOException noSharedRootYet) {
            return false;
        }
    }

    /** True when no other active grant still resolves to {@code path}. */
    private boolean lastGrantFor(Path path) {
        return grants.values().stream().noneMatch(grant -> grant.path.equals(path));
    }

    private FileRef register(String pluginId, Path path, String kind, String access, boolean owned) throws IOException {
        if (grants.size() >= MAX_ACTIVE_GRANTS) throw new IllegalStateException("Too many active file grants");
        String id = "ref_" + UUID.randomUUID();
        grants.put(id, new Grant(pluginId, path, kind, access, owned));
        versions.computeIfAbsent(pluginId, ignored -> new AtomicLong()).incrementAndGet();
        long size = Files.isRegularFile(path) ? Files.size(path) : 0;
        return new FileRef(id, path.getFileName().toString(), kind, access, size);
    }

    private static void enforceNativeQuota(Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            if (Files.size(path) > MAX_SINGLE_FILE_BYTES) throw new IllegalArgumentException("File exceeds 100 MB");
            return;
        }
        long total = 0;
        int count = 0;
        try (var paths = Files.walk(path)) {
            for (Path entry : paths.toList()) {
                if (!Files.isRegularFile(entry)) continue;
                if (++count > MAX_DIRECTORY_FILES) throw new IllegalArgumentException("Directory contains too many files");
                long size = Files.size(entry);
                if (size > MAX_SINGLE_FILE_BYTES) throw new IllegalArgumentException("Directory contains a file larger than 100 MB");
                total += size;
                if (total > MAX_GRANT_BYTES) throw new IllegalArgumentException("Directory exceeds 500 MB");
            }
        }
    }

    private Path ownedGrantRoot(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) return normalized;
        Path relative = root.relativize(normalized);
        return relative.getNameCount() >= 2 ? root.resolve(relative.subpath(0, 2)) : normalized;
    }

    private Path snapshot(String pluginId, Path source) throws IOException {
        Path snapshotRoot = Files.createDirectories(
            root.resolve(pluginId).resolve(UUID.randomUUID().toString()).resolve("in"));
        Path target = snapshotRoot.resolve(source.getFileName().toString());
        try {
            if (Files.isDirectory(source)) {
                try (var paths = Files.walk(source)) {
                    for (Path current : paths.toList()) {
                        if (Files.isSymbolicLink(current)) {
                            throw new IllegalArgumentException("Selected input contains a symbolic link");
                        }
                        Path copy = target.resolve(source.relativize(current).toString()).normalize();
                        if (!copy.startsWith(target)) throw new IllegalArgumentException("Invalid selected input path");
                        if (Files.isDirectory(current)) Files.createDirectories(copy);
                        else Files.copy(current, copy, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } else {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException | RuntimeException e) {
            deleteTree(snapshotRoot.getParent());
            throw e;
        }
    }

    /**
     * Best-effort recursive delete; never throws. Two revokes racing on the last grants of
     * the same {@code _shared} scratch directory, or a live worker still writing into the
     * tree, can make entries vanish mid-walk — that surfaces as {@code UncheckedIOException},
     * which the previous {@code throws IOException} shape let escape and abort the caller's
     * remaining revocations (worst case: the {@code @PreDestroy} sweep stopped early). A tree
     * that vanished mid-walk is already reclaimed; per-entry failures are skipped.
     */
    private static void deleteTree(Path directory) {
        List<Path> entries;
        try {
            if (!Files.exists(directory)) return;
            try (var paths = Files.walk(directory)) {
                entries = paths.sorted(Comparator.reverseOrder()).toList();
            }
        } catch (IOException | java.io.UncheckedIOException raced) {
            return; // deleted concurrently — nothing left to reclaim
        }
        for (Path path : entries) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // A busy entry (locked by a live writer) must not abort the rest of the tree.
            }
        }
    }

    @PreDestroy void close() throws IOException {
        for (var entry : List.copyOf(grants.entrySet())) revoke(entry.getValue().pluginId, entry.getKey());
    }

    public record FileRef(String id, String name, String kind, String access, long size) {}
    private record Grant(String pluginId, Path path, String kind, String access, boolean owned) {}
}
