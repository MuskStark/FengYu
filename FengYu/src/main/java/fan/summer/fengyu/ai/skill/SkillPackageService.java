package fan.summer.fengyu.ai.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.runtime.RuntimePaths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs, updates, enables/disables and removes isolated {@code .fys} skill packages.
 *
 * <p>The lifecycle twin of {@code PluginPackageService}: a skill is managed exactly like a plugin.
 * A {@code .fys} archive is a zip with a {@code manifest.json} ({@link SkillManifest}) and a
 * {@code SKILL.md} at its root. On install it is extracted under
 * {@code <programWorkingDirectory>/.fengyu/skills/<id>/} — a filesystem peer of
 * {@code <programWorkingDirectory>/.fengyu/plugins/<id>/} — via the same stage → validate →
 * atomic-publish → backup-rollback dance the plugin installer uses.
 * Enable state is a {@code .disabled} marker file (not a DB row), so it survives reinstall and
 * stays out of the JPA layer entirely.
 *
 * <p><b>Leaner than the plugin installer on purpose:</b> a skill carries no UI bundle, no worker,
 * no permissions, and no AI tools — it is pure guidance text. So the package-size ceiling is much
 * smaller (10 MB raw / 50 MB expanded vs the plugin's 100/300), there is no permission allow-list,
 * and validation only checks the manifest fields plus the presence of {@code SKILL.md}.
 *
 * @since 4.0.0
 */
@Service
public class SkillPackageService {
    private static final long MAX_PACKAGE_BYTES = 10L * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 50L * 1024 * 1024;

    /**
     * Entry-count twin of the expanded-bytes cap, mirroring the plugin installer's
     * {@code PluginPackageService.MAX_PACKAGE_ENTRIES}: the bytes cap cannot see zero-byte
     * entries, and a zip of millions of empty entries would exhaust inodes long before bytes.
     */
    private static final int MAX_PACKAGE_ENTRIES = 10_000;

    private final ObjectMapper json;
    private final Path root;

    public SkillPackageService(
            @Value("${fengyu.skills.directory:}") String directory) {
        this.json = JsonMapper.builder().findAndAddModules().build();
        this.root = directory == null || directory.isBlank()
                ? RuntimePaths.skillDirectory(RuntimePaths.root())
                : Path.of(directory).toAbsolutePath().normalize();
    }

    /** Lists every installed skill manifest, sorted by name (case-insensitive). */
    public List<SkillManifest> installed() {
        if (!Files.isDirectory(root)) return List.of();
        try (var dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory)
                .map(this::readManifestQuietly)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(SkillManifest::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read skill directory", e);
        }
    }

    public Optional<SkillManifest> find(String id) {
        Path dir = skillDir(id);
        if (!Files.isDirectory(dir)) return Optional.empty();
        return readManifestQuietly(dir);
    }

    /** Absolute skills root (transaction-rollback target beside {@link #directory}). */
    public Path root() {
        return root;
    }

    public Path directory(String id) {
        requireInstalled(id);
        return skillDir(id);
    }

    public SkillManifest install(MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("Skill package is empty");
        if (file.getSize() > MAX_PACKAGE_BYTES) throw new IllegalArgumentException("Skill package exceeds 10 MB");
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("");
        if (!name.toLowerCase(Locale.ROOT).endsWith(".fys")) {
            throw new IllegalArgumentException("Expected a .fys skill package");
        }
        try (InputStream input = file.getInputStream()) {
            return installArchive(input, false);
        }
    }

    public SkillManifest install(Path archive) throws IOException {
        if (!Files.isRegularFile(archive) || !archive.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".fys")) {
            throw new IllegalArgumentException("Expected a .fys skill package");
        }
        if (Files.size(archive) > MAX_PACKAGE_BYTES) throw new IllegalArgumentException("Skill package exceeds 10 MB");
        try (InputStream input = Files.newInputStream(archive)) {
            return installArchive(input, false);
        }
    }

    /**
     * Trusted install path (review M-6): only the verified marketplace and the
     * bundled official seeder may install a package that declares the official
     * identity — a user-uploaded or unsigned package cannot claim it.
     */
    public SkillManifest installTrusted(Path archive) throws IOException {
        if (!Files.isRegularFile(archive) || !archive.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".fys")) {
            throw new IllegalArgumentException("Expected a .fys skill package");
        }
        if (Files.size(archive) > MAX_PACKAGE_BYTES) throw new IllegalArgumentException("Skill package exceeds 10 MB");
        try (InputStream input = Files.newInputStream(archive)) {
            return installArchive(input, true);
        }
    }

    /** Enable state is a filesystem marker, mirroring {@code PluginPackageService.setEnabled}. */
    public void setEnabled(String id, boolean value) throws IOException {
        requireInstalled(id);
        Path marker = skillDir(id).resolve(".disabled");
        if (value) Files.deleteIfExists(marker);
        else Files.createFile(marker);
    }

    public boolean isEnabled(String id) {
        return !Files.exists(skillDir(id).resolve(".disabled"));
    }

    public void uninstall(String id) throws IOException {
        Path dir = skillDir(id);
        if (!Files.isDirectory(dir)) throw new IllegalArgumentException("Skill is not installed: " + id);
        deleteTree(dir);
    }

    // ── install internals (stage → validate → atomic publish → backup rollback) ──

    private SkillManifest installArchive(InputStream input, boolean trustedOfficial)
            throws IOException {
        Files.createDirectories(root);
        Path staging = Files.createTempDirectory(root, ".install-");
        try {
            extract(input, staging);
            // Runtime state belongs to the host and cannot be smuggled in by a package.
            Files.deleteIfExists(staging.resolve(".disabled"));
            SkillManifest manifest = readManifest(staging);
            validate(manifest, staging, trustedOfficial);
            Path destination = skillDir(manifest.id());
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
        int entries = 0;
        byte[] buffer = new byte[16 * 1024];
        try (ZipInputStream zip = new ZipInputStream(input)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (++entries > MAX_PACKAGE_ENTRIES) {
                    throw new IllegalArgumentException("Package contains more than "
                            + MAX_PACKAGE_ENTRIES + " entries");
                }
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
                        if (total > MAX_EXPANDED_BYTES) throw new IllegalArgumentException("Expanded package exceeds 50 MB");
                        output.write(buffer, 0, count);
                    }
                }
            }
        }
    }

    private SkillManifest readManifest(Path dir) throws IOException {
        Path path = dir.resolve("manifest.json");
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("manifest.json is missing");
        return json.readValue(path.toFile(), SkillManifest.class);
    }

    private Optional<SkillManifest> readManifestQuietly(Path dir) {
        try { return Optional.of(readManifest(dir)); }
        catch (Exception ignored) { return Optional.empty(); }
    }

    private void validate(SkillManifest m, Path staging, boolean trustedOfficial) {
        if (m.schemaVersion() != 1) throw new IllegalArgumentException("Unsupported skill manifest schemaVersion");
        if (m.id() == null || !m.id().matches("[a-z0-9]+(?:[.-][a-z0-9]+)+")) {
            throw new IllegalArgumentException("Skill id must be a lowercase reverse-domain identifier");
        }
        if (m.name() == null || m.name().isBlank()) throw new IllegalArgumentException("Skill name is required");
        if (m.official() && !trustedOfficial) {
            // Review M-6: the official identity is anchored to a verified signature
            // or the bundled seeder — a self-declared flag on an uploaded package
            // must not be able to impersonate shipped guidance.
            throw new IllegalArgumentException("Official skills are only installed "
                    + "through the verified marketplace or the bundled seeder");
        }
        if (m.official() && !m.id().startsWith("fan.summer.")) {
            throw new IllegalArgumentException("Official skill ids must use fan.summer.*");
        }
        if (isBuiltinSkillId(m.id())) {
            // Design §6.2: builtin skills cannot be overridden — their guidance is
            // part of the shipped product, and a remote package must never be able
            // to silently replace it in the AI's context.
            throw new IllegalArgumentException("Skill id '" + m.id()
                    + "' belongs to a builtin skill; builtin skills cannot be overridden");
        }
        if (m.version() == null || !m.version().matches("\\d+\\.\\d+\\.\\d+(?:[-+].+)?")) {
            throw new IllegalArgumentException("Skill version must be semantic versioning");
        }
        Path skillFile = staging.resolve("SKILL.md");
        if (!Files.isRegularFile(skillFile)) {
            throw new IllegalArgumentException("SKILL.md is required at the package root");
        }
    }

    private boolean isBuiltinSkillId(String id) {
        return getClass().getResource("/skills/" + id + "/SKILL.md") != null;
    }

    private void requireInstalled(String id) {
        if (!Files.isDirectory(skillDir(id))) throw new IllegalArgumentException("Skill is not installed: " + id);
    }

    private Path skillDir(String id) {
        if (id == null || !id.matches("[a-z0-9]+(?:[.-][a-z0-9]+)+")) throw new IllegalArgumentException("Invalid skill id");
        Path path = root.resolve(id).normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("Invalid skill id");
        return path;
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
