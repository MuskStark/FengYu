package fan.summer.fengyu.plugin.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.runtime.RuntimePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(PluginPackageService.class);
    private static final long MAX_PACKAGE_BYTES = 100L * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 300L * 1024 * 1024;
    private static final long MIN_TIMEOUT_SECONDS = 1L;
    private static final long MAX_TIMEOUT_SECONDS = 600L;
    private static final java.util.Set<String> ALLOWED_PERMISSIONS = java.util.Set.of(
        "files.read", "files.write", "network", "network.email",
        "clipboard.read", "clipboard.write", "notifications", "database");

    private final ObjectMapper json;
    private final Path root;
    private final HttpClient http;

    public PluginPackageService(
            @Value("${fengyu.plugins.directory:}") String directory) {
        // The manifest schema declares `additionalProperties: true`, so third-party packages may
        // carry forward-compatible fields the host doesn't model yet (e.g. a future `i18n` block
        // before the host upgraded). Tolerate unknown fields on read instead of failing the whole
        // install — a strict mapper would crash a package whose only offense is shipping a new field.
        this.json = JsonMapper.builder().findAndAddModules().build()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.root = directory == null || directory.isBlank()
                ? RuntimePaths.pluginDirectory(RuntimePaths.root())
                : Path.of(directory).toAbsolutePath().normalize();
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

    /**
     * Read a package's manifest without installing it, so a caller can compare versions and decide
     * whether an upgrade is worthwhile (e.g. the official-plugin seeder) before paying the cost of
     * a full extract-and-replace. Only the {@code manifest.json} entry is parsed.
     */
    public PluginManifest readArchiveManifest(Path archive) throws IOException {
        if (!Files.isRegularFile(archive)) throw new IllegalArgumentException("Plugin package not found: " + archive);
        try (InputStream input = Files.newInputStream(archive);
                ZipInputStream zip = new ZipInputStream(input)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if ("manifest.json".equals(entry.getName())) {
                    return json.readValue(zip.readAllBytes(), PluginManifest.class);
                }
            }
        }
        throw new IllegalArgumentException("manifest.json is missing in " + archive);
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
        catch (Exception e) {
            // Don't crash the installed() listing — one broken package shouldn't hide the rest.
            // But log the cause so a silently-skipped plugin (corrupt manifest, schema drift) is
            // debuggable instead of vanishing without a trace.
            log.warn("Skipping plugin at {}: could not read manifest.json ({})", dir, e.getMessage());
            return Optional.empty();
        }
    }

    private void validate(PluginManifest m, Path staging) {
        if (m.schemaVersion() != 1) throw new IllegalArgumentException("Unsupported manifest schemaVersion");
        if (m.id() == null || !m.id().matches("[a-z0-9]+(?:[.-][a-z0-9]+)+")) {
            throw new IllegalArgumentException("Plugin id must be a lowercase reverse-domain identifier");
        }
        if (m.name() == null || m.name().isBlank()) throw new IllegalArgumentException("Plugin name is required");
        if (m.description() == null || m.description().isBlank()) {
            throw new IllegalArgumentException("Plugin description is required");
        }
        if (m.author() == null || m.author().isBlank()) throw new IllegalArgumentException("Plugin author is required");
        if (m.icon() == null || m.icon().isBlank()) throw new IllegalArgumentException("Plugin icon is required");
        if (m.category() == null || m.category().isBlank()) throw new IllegalArgumentException("Plugin category is required");
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
        for (String permission : Optional.ofNullable(m.permissions()).orElse(List.of())) {
            if (!ALLOWED_PERMISSIONS.contains(permission)) {
                throw new IllegalArgumentException("Unknown plugin permission: " + permission);
            }
        }
        if (m.backend() != null) {
            if (m.backend().protocol() != null
                    && !"json-rpc-2.0".equals(m.backend().protocol())) {
                throw new IllegalArgumentException("Unsupported plugin backend protocol: " + m.backend().protocol());
            }
            validateTimeout(m.backend().callTimeoutSeconds(), "backend.callTimeoutSeconds");
        }
        java.util.Set<String> toolNames = new java.util.HashSet<>();
        java.util.Set<String> toolMethods = new java.util.HashSet<>();
        for (PluginManifest.AiTool tool : Optional.ofNullable(m.aiTools()).orElse(List.of())) {
            if (tool.name() == null || tool.name().isBlank() || !toolNames.add(tool.name())) {
                throw new IllegalArgumentException("Invalid or duplicate AI tool name: " + tool.name());
            }
            if (tool.method() == null || tool.method().isBlank() || !toolMethods.add(tool.method())) {
                throw new IllegalArgumentException("Invalid or duplicate AI tool method: " + tool.method());
            }
            try {
                com.fasterxml.jackson.databind.JsonNode schemaNode = new com.fasterxml.jackson.databind.ObjectMapper().readTree(tool.inputSchema());
                if (!(schemaNode.has("type") && "object".equals(schemaNode.get("type").asText()))) {
                    throw new IllegalArgumentException("AI tool inputSchema must be a JSON object: " + tool.name());
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalArgumentException("Invalid inputSchema for AI tool " + tool.name(), e);
            }
            if (tool.outputSchema() != null) {
                try {
                    com.fasterxml.jackson.databind.JsonNode schemaNode = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(tool.outputSchema());
                    if (!(schemaNode.has("type") && "object".equals(schemaNode.get("type").asText()))) {
                        throw new IllegalArgumentException("AI tool outputSchema must be a JSON object: " + tool.name());
                    }
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new IllegalArgumentException("Invalid outputSchema for AI tool " + tool.name(), e);
                }
            }
            validateTimeout(tool.timeoutSeconds(), "aiTools[" + tool.name() + "].timeoutSeconds");
            if (tool.effect() != null && !java.util.Set.of("read", "write", "external").contains(tool.effect())) {
                throw new IllegalArgumentException("Invalid effect for AI tool " + tool.name());
            }
        }
    }

    private static void validateTimeout(Long seconds, String field) {
        if (seconds == null) return;
        if (seconds < MIN_TIMEOUT_SECONDS || seconds > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException(field + " must be between "
                + MIN_TIMEOUT_SECONDS + " and " + MAX_TIMEOUT_SECONDS + " seconds");
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
