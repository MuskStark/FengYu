package fan.summer.fengyu.plugin.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs, updates and removes isolated .fyp plugin packages. */
@Service
public class PluginPackageService {
    private static final long MAX_PACKAGE_BYTES = 100L * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 300L * 1024 * 1024;

    private final ObjectMapper json;
    private final Path root;
    private final HttpClient http;

    public PluginPackageService(
            @Value("${fengyu.plugins.directory:${user.home}/.fengyu/plugins}") String directory) {
        this.json = JsonMapper.builder().findAndAddModules().build();
        this.root = Path.of(directory).toAbsolutePath().normalize();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    public List<PluginManifest> installed() {
        if (!Files.isDirectory(root)) return List.of();
        try (var dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory)
                .map(this::readManifestQuietly)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(PluginManifest::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read plugin directory", e);
        }
    }

    public Optional<PluginManifest> find(String id) {
        Path dir = pluginDir(id);
        if (!Files.isDirectory(dir)) return Optional.empty();
        return readManifestQuietly(dir);
    }

    public Path directory(String id) {
        requireInstalled(id);
        return pluginDir(id);
    }

    public Path asset(String id, String relativePath) {
        Path base = directory(id);
        Path asset = base.resolve(relativePath).normalize();
        if (!asset.startsWith(base)) throw new IllegalArgumentException("Invalid plugin asset path");
        return asset;
    }

    public PluginManifest install(MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("Plugin package is empty");
        if (file.getSize() > MAX_PACKAGE_BYTES) throw new IllegalArgumentException("Plugin package exceeds 100 MB");
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("");
        if (!name.toLowerCase(Locale.ROOT).endsWith(".fyp")) {
            throw new IllegalArgumentException("Expected a .fyp plugin package");
        }
        try (InputStream input = file.getInputStream()) {
            return installArchive(input);
        }
    }

    public PluginManifest install(Path archive) throws IOException {
        if (!Files.isRegularFile(archive) || !archive.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".fyp")) {
            throw new IllegalArgumentException("Expected a .fyp plugin package");
        }
        if (Files.size(archive) > MAX_PACKAGE_BYTES) throw new IllegalArgumentException("Plugin package exceeds 100 MB");
        try (InputStream input = Files.newInputStream(archive)) { return installArchive(input); }
    }

    public PluginManifest installFromUrl(String url) throws IOException, InterruptedException {
        URI uri = URI.create(url);
        if (!List.of("https", "http").contains(uri.getScheme())) {
            throw new IllegalArgumentException("Plugin download URL must use HTTP(S)");
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2)).GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalArgumentException("Plugin download failed with HTTP " + response.statusCode());
        }
        long size = response.headers().firstValueAsLong("content-length").orElse(-1);
        if (size > MAX_PACKAGE_BYTES) throw new IllegalArgumentException("Plugin package exceeds 100 MB");
        try (InputStream body = response.body()) {
            return installArchive(body);
        }
    }

    public void setEnabled(String id, boolean value) throws IOException {
        requireInstalled(id);
        Path marker = pluginDir(id).resolve(".disabled");
        if (value) Files.deleteIfExists(marker);
        else Files.createFile(marker);
    }

    public boolean isEnabled(String id) {
        return !Files.exists(pluginDir(id).resolve(".disabled"));
    }

    public void uninstall(String id) throws IOException {
        Path dir = pluginDir(id);
        if (!Files.isDirectory(dir)) throw new IllegalArgumentException("Plugin is not installed: " + id);
        deleteTree(dir);
    }

    private PluginManifest installArchive(InputStream input) throws IOException {
        Files.createDirectories(root);
        Path staging = Files.createTempDirectory(root, ".install-");
        try {
            extract(input, staging);
            // Runtime state belongs to the host and cannot be smuggled in by a package.
            Files.deleteIfExists(staging.resolve(".disabled"));
            PluginManifest manifest = readManifest(staging);
            validate(manifest, staging);
            Path destination = pluginDir(manifest.id());
            Path backup = root.resolve(".backup-" + manifest.id());
            boolean wasEnabled = !Files.exists(destination.resolve(".disabled"));
            if (Files.exists(backup)) deleteTree(backup);
            if (Files.exists(destination)) Files.move(destination, backup, StandardCopyOption.ATOMIC_MOVE);
            try {
                Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
                if (!wasEnabled) Files.createFile(destination.resolve(".disabled"));
                if (Files.exists(backup)) deleteTree(backup);
            } catch (IOException e) {
                if (Files.exists(backup) && !Files.exists(destination)) {
                    Files.move(backup, destination, StandardCopyOption.ATOMIC_MOVE);
                }
                throw e;
            }
            return manifest;
        } finally {
            if (Files.exists(staging)) deleteTree(staging);
        }
    }

    private void extract(InputStream input, Path staging) throws IOException {
        long total = 0;
        byte[] buffer = new byte[16 * 1024];
        try (ZipInputStream zip = new ZipInputStream(input)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                Path target = staging.resolve(entry.getName()).normalize();
                if (!target.startsWith(staging)) throw new IllegalArgumentException("Package contains an unsafe path");
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (var output = Files.newOutputStream(target)) {
                    int count;
                    while ((count = zip.read(buffer)) >= 0) {
                        total += count;
                        if (total > MAX_EXPANDED_BYTES) throw new IllegalArgumentException("Expanded package exceeds 300 MB");
                        output.write(buffer, 0, count);
                    }
                }
            }
        }
    }

    private PluginManifest readManifest(Path dir) throws IOException {
        Path path = dir.resolve("manifest.json");
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("manifest.json is missing");
        return json.readValue(path.toFile(), PluginManifest.class);
    }

    private Optional<PluginManifest> readManifestQuietly(Path dir) {
        try { return Optional.of(readManifest(dir)); }
        catch (Exception ignored) { return Optional.empty(); }
    }

    private void validate(PluginManifest m, Path staging) {
        if (m.schemaVersion() != 1) throw new IllegalArgumentException("Unsupported manifest schemaVersion");
        if (m.id() == null || !m.id().matches("[a-z0-9]+(?:[.-][a-z0-9]+)+")) {
            throw new IllegalArgumentException("Plugin id must be a lowercase reverse-domain identifier");
        }
        if (m.name() == null || m.name().isBlank()) throw new IllegalArgumentException("Plugin name is required");
        if (m.official() && !m.id().startsWith("fan.summer.")) throw new IllegalArgumentException("Official plugin ids must use fan.summer.*");
        if (m.version() == null || !m.version().matches("\\d+\\.\\d+\\.\\d+(?:[-+].+)?")) {
            throw new IllegalArgumentException("Plugin version must be semantic versioning");
        }
        if (m.ui() == null || m.ui().entry() == null || m.ui().entry().isBlank()) {
            throw new IllegalArgumentException("Plugin UI entry is required");
        }
        Path ui = staging.resolve(m.ui().entry()).normalize();
        if (!ui.startsWith(staging) || !Files.isRegularFile(ui)) {
            throw new IllegalArgumentException("Plugin UI entry does not exist");
        }
    }

    private void requireInstalled(String id) {
        if (!Files.isDirectory(pluginDir(id))) throw new IllegalArgumentException("Plugin is not installed: " + id);
    }

    private Path pluginDir(String id) {
        if (id == null || !id.matches("[a-z0-9]+(?:[.-][a-z0-9]+)+")) throw new IllegalArgumentException("Invalid plugin id");
        Path path = root.resolve(id).normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("Invalid plugin id");
        return path;
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
