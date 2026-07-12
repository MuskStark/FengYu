package fan.summer.fengyu.plugin.workspace;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.*;

/** Manages per-plugin per-session file workspaces under the OS temp dir, plus zip packaging
 *  and TTL cleanup. Backs the web upload/download path of the plugin file I/O standard. */
@Service
public class PluginWorkspaceService {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private final Path root = Paths.get(System.getProperty("java.io.tmpdir"), "fengyu", "plugin-workspace");

    public String newSession() { return UUID.randomUUID().toString(); }

    private void checkToken(String s, String what) {
        if (s == null || !SAFE_ID.matcher(s).matches() || s.equals(".") || s.equals("..")) {
            throw new IllegalArgumentException("Invalid " + what + ": " + s);
        }
    }

    private Path sessionRoot(String pluginId, String session) {
        checkToken(pluginId, "pluginId");
        checkToken(session, "session");
        Path normalizedRoot = root.normalize();
        Path resolved = normalizedRoot.resolve(pluginId).resolve(session).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Resolved workspace path escapes root: " + resolved);
        }
        return resolved;
    }

    public Path inDir(String pluginId, String session) {
        return ensure(sessionRoot(pluginId, session).resolve("in"));
    }

    public Path outDir(String pluginId, String session) {
        return ensure(sessionRoot(pluginId, session).resolve("out"));
    }

    private static Path ensure(Path p) {
        try { Files.createDirectories(p); } catch (IOException e) { throw new UncheckedIOException(e); }
        return p;
    }

    public Path store(String pluginId, String session, String filename, InputStream data) throws IOException {
        if (filename == null || filename.isBlank()
                || filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            throw new IllegalArgumentException("Unsafe filename: " + filename);
        }
        Path target = inDir(pluginId, session).resolve(filename);
        try (data) { Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING); }
        return target;
    }

    public void zipDir(Path dir, OutputStream out) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            if (!Files.isDirectory(dir)) return;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path p : ds) {
                    if (!Files.isRegularFile(p)) continue;
                    zos.putNextEntry(new ZipEntry(p.getFileName().toString()));
                    Files.copy(p, zos);
                    zos.closeEntry();
                }
            }
        }
    }

    public void remove(String pluginId, String session) {
        deleteRecursive(sessionRoot(pluginId, session));
    }

    public void sweep(Duration ttl) {
        if (!Files.isDirectory(root)) return;
        Instant cutoff = Instant.now().minus(ttl);
        try (var pluginDirs = Files.newDirectoryStream(root)) {
            for (Path pd : pluginDirs) {
                try (var sessDirs = Files.newDirectoryStream(pd)) {
                    for (Path sd : sessDirs) {
                        BasicFileAttributes a = Files.readAttributes(sd, BasicFileAttributes.class);
                        if (a.lastModifiedTime().toInstant().isBefore(cutoff)) deleteRecursive(sd);
                    }
                }
            }
        } catch (IOException ignored) { }
    }

    @PreDestroy
    public void shutdown() { deleteRecursive(root); }

    private static void deleteRecursive(Path p) {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(x -> { try { Files.deleteIfExists(x); } catch (IOException ignored) {} });
        } catch (IOException ignored) { }
    }
}
