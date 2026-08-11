package fan.summer.fengyu.plugin.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.runtime.RuntimePaths;
import fan.summer.fengyu.setup.PluginDbProvisioner;
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
    /**
     * Permission tokens accepted by a plugin manifest. The enforcement matrix (P1-9):
     * <ul>
     *   <li><strong>Enforced by the host/OS sandbox:</strong> {@code files.read},
     *       {@code files.write} (FileRef grant gate), {@code network} (OS network namespace).</li>
     *   <li><strong>Treated as full network egress (advisory at the network layer):</strong>
     *       {@code network.email}, {@code database}. A real SMTP/IMAP broker and DB-host allowlist
     *       are tracked follow-ups; today these grant broad egress, so the UI must not imply finer
     *       isolation than the OS enforces.</li>
     *   <li><strong>Advisory only (no host enforcement yet):</strong> {@code clipboard.read},
     *       {@code clipboard.write}, {@code notifications}. No host capability or OS gate reads
     *       these at runtime; they document intent for a future capability bridge to the desktop
     *       shell.</li>
     * </ul>
     */
    private static final java.util.Set<String> ALLOWED_PERMISSIONS = java.util.Set.of(
        "files.read", "files.write", "network", "network.email",
        "clipboard.read", "clipboard.write", "notifications", "database");

    private final ObjectMapper json;
    private final Path root;
    private final HttpClient http;
    private PluginDbProvisioner dbProvisioner;  // nullable; null when no DB isolation is active
    private PluginIntegrityStore integrityStore;  // nullable; null in some tests
    /**
     * Sibling data root ({@code .fengyu/plugin-data}). Each plugin's runtime state (embedded SQLite
     * files, browser profiles/cookies, screenshots, mail keys) lives under {@code <dataRoot>/<id>}.
     * Uninstall applies the caller's explicit retain/delete policy to this directory (P1-4); the
     * old code either always left it behind or later deleted it without giving the user a choice.
     */
    private final Path dataRoot;

    public PluginPackageService(
            @Value("${fengyu.plugins.directory:}") String directory) {
        this(directory, RuntimePaths.pluginDataDirectory(RuntimePaths.root()));
    }

    /** Test seam for verifying uninstall data-retention policy without touching the real runtime. */
    PluginPackageService(String directory, Path dataRoot) {
        // The manifest schema declares `additionalProperties: true`, so third-party packages may
        // carry forward-compatible fields the host doesn't model yet (e.g. a future `i18n` block
        // before the host upgraded). Tolerate unknown fields on read instead of failing the whole
        // install — a strict mapper would crash a package whose only offense is shipping a new field.
        this.json = JsonMapper.builder().findAndAddModules().build()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.root = directory == null || directory.isBlank()
                ? RuntimePaths.pluginDirectory(RuntimePaths.root())
                : Path.of(directory).toAbsolutePath().normalize();
        this.dataRoot = dataRoot.toAbsolutePath().normalize();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /**
     * Spring-injection constructor: wires the optional DB provisioner so {@link #uninstall} can
     * deprovision plugin DB credentials, and the integrity store so installs record a manifest
     * digest the host re-verifies before starting a Worker (P0-2 tamper detection). Each is a
     * separate bean; in SETUP mode or in tests that use the single-arg constructor they stay null.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public PluginPackageService(
            @Value("${fengyu.plugins.directory:}") String directory,
            PluginDbProvisioner provisioner,
            PluginIntegrityStore integrityStore) {
        this(directory);
        this.dbProvisioner = provisioner;
        this.integrityStore = integrityStore;
    }

    /** Test-only: attach a provisioner so uninstall can be asserted to deprovision. */
    void attachProvisionerForTest(PluginDbProvisioner provisioner) {
        this.dbProvisioner = provisioner;
    }

    /** Test-only: attach an integrity store so install/verify can be exercised in isolation. */
    public void attachIntegrityStoreForTest(PluginIntegrityStore integrityStore) {
        this.integrityStore = integrityStore;
    }

    /** The integrity store, if wired; null in tests that use the single-arg constructor. */
    public PluginIntegrityStore integrityStore() {
        return integrityStore;
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
        // A matching `.fyp.sha256` sidecar (written by the CLI packager, verified by the official
        // seeder) is the trust credential that lets a local install claim official identity /
        // the fan.summer.* namespace. Without it the install stays untrusted, so the existing
        // validate() reservation blocks official/namespace-squatting. Multipart upload cannot
        // carry a sidecar and stays untrusted; official plugins are installed via this native path.
        boolean trusted = verifySidecar(archive);
        try (InputStream input = Files.newInputStream(archive)) { return installArchive(input, trusted); }
    }

    /**
     * Install a package from a host-trusted source (the official-plugin seeder). Trusted installs
     * may declare {@code official: true} and use the reserved {@code fan.summer.*} namespace; the
     * seeder verifies a SHA-256 sidecar before calling this, so the package's identity claims are
     * trusted. User uploads/marketplace installs must go through {@link #install(MultipartFile)} /
     * {@link #install(Path)} (untrusted) and cannot claim either.
     */
    public PluginManifest installTrusted(Path archive) throws IOException {
        if (!Files.isRegularFile(archive) || !archive.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".fyp")) {
            throw new IllegalArgumentException("Expected a .fyp plugin package");
        }
        if (Files.size(archive) > MAX_PACKAGE_BYTES) throw new IllegalArgumentException("Plugin package exceeds 100 MB");
        try (InputStream input = Files.newInputStream(archive)) { return installArchive(input, true); }
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

    /**
     * Read an uploaded package's manifest without installing it (P0-6). Used to learn the incoming
     * plugin id before a replace-style upload so the host can stop the running Worker first.
     */
    public PluginManifest readArchiveManifest(MultipartFile file) throws IOException {
        try (InputStream input = file.getInputStream();
                ZipInputStream zip = new ZipInputStream(input)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if ("manifest.json".equals(entry.getName())) {
                    return json.readValue(zip.readAllBytes(), PluginManifest.class);
                }
            }
        }
        throw new IllegalArgumentException("manifest.json is missing in the uploaded package");
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

    /** Backwards-compatible internal default: remove both package and runtime data. */
    public void uninstall(String id) throws IOException {
        uninstall(id, true);
    }

    /**
     * Uninstall a plugin with an explicit runtime-data policy (P1-4).
     *
     * @param deleteData when true, delete {@code plugin-data/<id>} and surface any failure to the
     *                   caller; when false, retain runtime state for a later reinstall
     */
    public void uninstall(String id, boolean deleteData) throws IOException {
        Path dir = pluginDir(id);
        if (!Files.isDirectory(dir)) throw new IllegalArgumentException("Plugin is not installed: " + id);
        // Database namespace/credentials are plugin-owned runtime data too. Retain them when the
        // user selected retain-data so a later reinstall can reconnect to the same state; only a
        // delete-data uninstall requests deprovisioning.
        if (deleteData && dbProvisioner != null) {
            try {
                dbProvisioner.deprovision(id);
            } catch (RuntimeException e) {
                log.warn("DB deprovision for {} failed; continuing with file removal: {}", id, e.getMessage());
            }
        }
        // Delete user-selected runtime data before removing the package. A failure is not swallowed:
        // returning success while profiles/credentials/files remain would falsely tell the user the
        // requested data deletion completed. Keeping the package makes the operation retryable.
        if (deleteData) {
            Path dataDir = dataRoot.resolve(id).normalize();
            if (!dataDir.startsWith(dataRoot)) {
                throw new IOException("Refusing to delete plugin data outside the runtime data root");
            }
            if (Files.exists(dataDir)) deleteTree(dataDir);
        }
        deleteTree(dir);
        // Drop the manifest-digest record so a future reinstall with the same id starts clean.
        if (integrityStore != null) integrityStore.forget(id);
        // Write an uninstall tombstone so the official-plugin seeder does not re-seed the bundled
        // archive on the next restart. Without it the seeder cannot distinguish a user uninstall
        // from a never-installed plugin (both leave no package dir and no integrity record).
        if (integrityStore != null) integrityStore.markUninstalled(id);
    }

    private PluginManifest installArchive(InputStream input) throws IOException {
        return installArchive(input, false);
    }

    /**
     * Install an archive with an explicit trust marker.
     *
     * @param trustedSource {@code true} when the install was produced by a host-trusted path
     *                      (the bundled official-plugin seeder, which verifies a SHA-256 sidecar
     *                      before installing). {@code false} for user uploads, marketplace
     *                      installs, and URL installs — these cannot claim {@code official:true}
     *                      or the reserved {@code fan.summer.*} namespace.
     */
    PluginManifest installArchive(InputStream input, boolean trustedSource) throws IOException {
        Files.createDirectories(root);
        Path staging = Files.createTempDirectory(root, ".install-");
        try {
            extract(input, staging);
            // Runtime state belongs to the host and cannot be smuggled in by a package.
            Files.deleteIfExists(staging.resolve(".disabled"));
            PluginManifest manifest = readManifest(staging);
            validate(manifest, staging, trustedSource);
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
            // Record the installed manifest's digest so the host can detect a runtime tamper of
            // manifest.json (a Worker must not be able to rewrite its own manifest and escalate).
            // P0-2: recorded only after the atomic swap succeeds, so a failed install leaves no
            // record that could later mask a tampered package. P0-6: the package directory digest
            // is also recorded so the Worker cache can key on content (a same-version repack with
            // different bytes gets a different digest → the stale Worker is invalidated).
            if (integrityStore != null) {
                integrityStore.record(manifest.id(), manifest.version(), destination.resolve("manifest.json"), destination);
                // A reinstall (local/online/seeder) clears any prior uninstall tombstone so the
                // official-plugin seeder's normal upgrade path resumes and a future uninstall is
                // honoured again. Paired with uninstall()'s markUninstalled().
                integrityStore.clearUninstalled(manifest.id());
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

    private void validate(PluginManifest m, Path staging, boolean trustedSource) {
        // T2-04 bullet 1: the host accepts ONLY schema v2.
        if (m.schemaVersion() != 2) throw new IllegalArgumentException("Unsupported manifest schemaVersion");
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
        // P0-8: official identity is reserved and cannot be self-declared by an uploaded/marketplace
        // package. The `official` flag and the `fan.summer.*` namespace are host-trusted only — a
        // package that claims either without coming through a trusted path (the official-plugin
        // seeder, which verifies a SHA-256 sidecar) is rejected, so no third party can masquerade as
        // an official plugin or squat the official namespace. Asymmetric signature verification is a
        // tracked follow-up; namespace reservation + official-claim rejection close the impersonation
        // hole today.
        if (!trustedSource) {
            if (m.official()) {
                throw new IllegalArgumentException(
                    "Plugin declares 'official: true' but was installed via an untrusted path "
                        + "(upload/marketplace). Only host-trusted (signed) packages may be official.");
            }
            if (m.id().startsWith("fan.summer.")) {
                throw new IllegalArgumentException(
                    "Plugin id uses the reserved 'fan.summer.*' namespace but was installed via an "
                        + "untrusted path (upload/marketplace). Choose a different reverse-domain id.");
            }
        } else if (m.official() && !m.id().startsWith("fan.summer.")) {
            // A trusted install may legitimately be official, but the id must still be in-namespace.
            throw new IllegalArgumentException("Official plugin ids must use fan.summer.*");
        }
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
            validateTimeout(m.backend().callTimeoutSeconds(), "backend.callTimeoutSeconds");
        }
        // T2-04: validate the rpc.methods table. Each method's inputSchema must be a JSON-Schema
        // object (read directly from the parsed JsonNode — no string re-parsing). A backend with no
        // callable method is invalid (a worker that cannot be invoked serves no purpose).
        java.util.Map<String, PluginManifest.RpcMethod> methods = m.rpc() != null ? m.rpc().methods() : null;
        if (m.backend() != null && (methods == null || methods.isEmpty())) {
            throw new IllegalArgumentException(
                "Plugin declares a backend but no rpc.methods — a worker must expose at least one method");
        }
        if (methods != null) {
            for (var entry : methods.entrySet()) {
                String methodName = entry.getKey();
                PluginManifest.RpcMethod method = entry.getValue();
                if (!isObjectSchema(method.inputSchema())) {
                    throw new IllegalArgumentException(
                        "rpc.methods." + methodName + ".inputSchema must be a JSON object schema");
                }
                if (method.outputSchema() != null && !isObjectSchema(method.outputSchema())) {
                    throw new IllegalArgumentException(
                        "rpc.methods." + methodName + ".outputSchema must be a JSON object schema");
                }
                validateTimeout(method.timeoutSeconds(), "rpc.methods." + methodName + ".timeoutSeconds");
            }
        }
        java.util.Set<String> toolNames = new java.util.HashSet<>();
        for (PluginManifest.AiTool tool : Optional.ofNullable(m.aiTools()).orElse(List.of())) {
            if (tool.name() == null || tool.name().isBlank() || !toolNames.add(tool.name())) {
                throw new IllegalArgumentException("Invalid or duplicate AI tool name: " + tool.name());
            }
            if (tool.method() == null || tool.method().isBlank()) {
                throw new IllegalArgumentException("Invalid AI tool method: " + tool.name());
            }
            // T2-04 bullet 3: the input schema is resolved from the referenced rpc method's OBJECT
            // schema — there is no inline string to parse. A dangling method reference (the tool
            // points at a method that does not exist in rpc.methods) is rejected at install time.
            if (methods == null || !methods.containsKey(tool.method())) {
                throw new IllegalArgumentException(
                    "AI tool " + tool.name() + " references unknown method: " + tool.method());
            }
            // v2 makes effect mandatory authorization metadata.
            if (tool.effect() == null
                    || !java.util.Set.of("read", "write", "external").contains(tool.effect())) {
                throw new IllegalArgumentException("Invalid effect for AI tool " + tool.name());
            }
            validateTimeout(tool.timeoutSeconds(), "aiTools[" + tool.name() + "].timeoutSeconds");
        }
    }

    /** A JsonNode is a valid OBJECT input/output schema when it has {@code type:"object"}. */
    private static boolean isObjectSchema(com.fasterxml.jackson.databind.JsonNode schema) {
        return schema != null && schema.isObject()
                && schema.has("type") && "object".equals(schema.get("type").asText());
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

    /**
     * Verify a local {@code .fyp} archive against a sibling {@code <archive>.sha256} sidecar. The
     * sidecar is the CLI packager's trust credential (GNU coreutils {@code sha256sum -c} format:
     * {@code <hex>  <basename>}); a present-and-matching sidecar lets the install claim official
     * identity / the {@code fan.summer.*} namespace via {@code trustedSource=true}. This is the same
     * check the official seeder performs on bundled archives, so a user can install a rebuilt
     * official plugin locally through the same trust level.
     *
     * <p>Returns {@code false} (never throws) when the sidecar is absent or mismatched — the caller
     * then installs as untrusted and {@code validate()}'s official/namespace reservation applies.
     * This is a tamper/corruption check, not an independent authenticity anchor (an attacker who can
     * replace both files can make them agree); asymmetric signature verification remains a tracked
     * follow-up.
     */
    static boolean verifySidecar(Path archive) {
        Path sidecar = Path.of(archive + ".sha256");
        if (!Files.isRegularFile(sidecar)) return false;
        try {
            String expected = parseFirstToken(Files.readString(sidecar).trim());
            if (expected == null) return false;
            return expected.equalsIgnoreCase(sha256Hex(archive));
        } catch (IOException e) {
            log.warn("Cannot verify .sha256 sidecar for {}: {}", archive, e.getMessage());
            return false;
        }
    }

    /** The first whitespace-delimited token of a {@code sha256sum} line (the hex digest). */
    private static String parseFirstToken(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (Character.isWhitespace(line.charAt(i))) return line.substring(0, i);
        }
        return line.isEmpty() ? null : line;
    }

    /** Compute the SHA-256 hex digest of a file's bytes. */
    private static String sha256Hex(Path file) throws IOException {
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int count;
            while ((count = in.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
