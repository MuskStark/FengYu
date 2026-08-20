package fan.summer.fengyu.plugin.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.runtime.RuntimePaths;
import fan.summer.fengyu.plugin.runtime.PluginWorkerProtocol;
import fan.summer.fengyu.setup.PluginDbProvisioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
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
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs, updates and removes isolated .fyp plugin packages. */
@Service
public class PluginPackageService {
    private static final Logger log = LoggerFactory.getLogger(PluginPackageService.class);
    private static final long MAX_PACKAGE_BYTES = 100L * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 300L * 1024 * 1024;
    static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    /** Entry-count ceiling for one package — bounds inode exhaustion from empty entries. */
    static final int MAX_PACKAGE_ENTRIES = 10_000;
    /** Ceiling for an uploaded {@code .fyp.sha256} sidecar — one digest line is under 100 bytes. */
    private static final long MAX_SIDECAR_BYTES = 1024 * 1024;
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
     *   <li><strong>Advisory (accepted but not enforced):</strong> {@code notifications}.
     *       The plugin notify bridge delivers EVERY plugin's notification through the unified
     *       host pipeline (toast + native desktop notification + persisted center) — the old
     *       permission gate routed undeclared plugins to an iframe-internal fallback whose
     *       snackbars the user could not see. The token remains allowed so existing manifests
     *       keep installing; it documents intent only.</li>
     *   <li><strong>Advisory only (no host enforcement yet):</strong> {@code clipboard.read},
     *       {@code clipboard.write}. No host capability or OS gate reads these at runtime;
     *       they document intent for a future capability bridge to the desktop shell.</li>
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
    private PluginTrustStore trustStore;  // nullable in tests using the lightweight constructor
    /**
     * Sibling data root ({@code .fengyu/plugin-data}). Each plugin's runtime state (embedded SQLite
     * files, browser profiles/cookies, screenshots, mail keys) lives under {@code <dataRoot>/<id>}.
     * Uninstall applies the caller's explicit retain/delete policy to this directory (P1-4); the
     * old code either always left it behind or later deleted it without giving the user a choice.
     */
    private final Path dataRoot;
    private final Path rollbackRoot;
    private final Path transactionRoot;
    /** Packages restored before the Spring-managed integrity store was attached. */
    private final Set<String> recoveredUpdates = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public PluginPackageService(
            @Value("${fengyu.plugins.directory:}") String directory) {
        this(directory, RuntimePaths.pluginDataDirectory(RuntimePaths.root()));
    }

    /** Test seam for verifying uninstall data-retention policy without touching the real runtime. */
    PluginPackageService(String directory, Path dataRoot) {
        // DELIBERATELY more lenient than toolchain/spec/manifest.schema.json (which declares
        // `additionalProperties: false` to keep the contract total for generators/CLI): the
        // host tolerates unknown fields on read so a package validated by a NEWER spec still
        // installs on an older host instead of failing over a field this build doesn't model.
        // The divergence is one-directional and safe (host-accepted ⊇ spec-valid).
        this.json = JsonMapper.builder().findAndAddModules().build()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.root = directory == null || directory.isBlank()
                ? RuntimePaths.pluginDirectory(RuntimePaths.root())
                : Path.of(directory).toAbsolutePath().normalize();
        this.dataRoot = dataRoot.toAbsolutePath().normalize();
        this.rollbackRoot = this.root.resolve(".rollback");
        this.transactionRoot = this.root.resolve(".transactions");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        recoverInterruptedUpdates();
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
            PluginIntegrityStore integrityStore,
            PluginTrustStore trustStore) {
        this(directory);
        this.dbProvisioner = provisioner;
        this.integrityStore = integrityStore;
        this.trustStore = trustStore;
        recordRecoveredIntegrity();
    }

    /** Test-only: attach a provisioner so uninstall can be asserted to deprovision. */
    void attachProvisionerForTest(PluginDbProvisioner provisioner) {
        this.dbProvisioner = provisioner;
    }

    /** Test-only: attach an integrity store so install/verify can be exercised in isolation. */
    public void attachIntegrityStoreForTest(PluginIntegrityStore integrityStore) {
        this.integrityStore = integrityStore;
        recordRecoveredIntegrity();
    }

    void attachTrustStoreForTest(PluginTrustStore trustStore) {
        this.trustStore = trustStore;
    }

    /** The integrity store, if wired; null in tests that use the single-arg constructor. */
    public PluginIntegrityStore integrityStore() {
        return integrityStore;
    }

    public List<PluginManifest> installed() {
        if (!Files.isDirectory(root)) return List.of();
        try (var dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory)
                .filter(path -> !path.getFileName().toString().startsWith("."))
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
        return install(file, null);
    }

    /**
     * Installs an uploaded package, optionally verifying a packager-written
     * {@code .sha256} sidecar. When the user enabled the supply-chain policy
     * ({@code marketplace.require_checksum}), a missing or mismatching sidecar rejects
     * the install — the pin can only tighten, mirroring pinned-source policies.
     */
    public PluginManifest install(MultipartFile file, MultipartFile checksumSidecar) throws IOException {
        return install(file, checksumSidecar, false);
    }

    public PluginManifest install(MultipartFile file, MultipartFile checksumSidecar,
            boolean confirmPermissionEscalation) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("Plugin package is empty");
        if (file.getSize() > MAX_PACKAGE_BYTES) throw new IllegalArgumentException("Plugin package exceeds 100 MB");
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("");
        if (!name.toLowerCase(Locale.ROOT).endsWith(".fyp")) {
            throw new IllegalArgumentException("Expected a .fyp plugin package");
        }
        boolean enforcement = fan.summer.fengyu.ai.service.AiConfigServiceHeadless
                .isMarketplaceChecksumRequired();
        boolean sidecarPresent = checksumSidecar != null && !checksumSidecar.isEmpty();
        if (enforcement || sidecarPresent) {
            // A PRESENT sidecar is always verified (mismatch rejects even with enforcement
            // off); with enforcement on, its presence is also mandatory.
            verifySidecar(file, checksumSidecar);
        }
        try (InputStream input = file.getInputStream()) {
            return installArchive(input, false, null, confirmPermissionEscalation);
        }
    }

    /**
     * Sidecar format is the sha256sum convention: {@code <hex>  <filename>}. The sidecar is
     * read with an explicit size ceiling — the digest line is under a hundred bytes, so
     * anything larger is a malformed upload and must not rely on the global multipart limits
     * to be rejected.
     */
    private static void verifySidecar(MultipartFile archive, MultipartFile sidecar) throws IOException {
        if (sidecar == null || sidecar.isEmpty()) {
            throw new IllegalArgumentException(
                    "Checksum enforcement is enabled: the package must be uploaded together with "
                            + "its .fyp.sha256 sidecar (produced by the fengyu CLI packager)");
        }
        if (sidecar.getSize() > MAX_SIDECAR_BYTES) {
            throw new IllegalArgumentException(
                    "The .fyp.sha256 sidecar exceeds " + MAX_SIDECAR_BYTES
                            + " bytes — a checksum file is one digest line");
        }
        String expected = PluginIntegrityStore.sha256Hex(archive.getInputStream());
        String declared = new String(sidecar.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8).trim();
        String declaredHex = declared.split("\\s+")[0];
        if (!declaredHex.equalsIgnoreCase(expected)) {
            throw new IllegalArgumentException(
                    "Checksum mismatch: package digest " + expected + " does not match sidecar "
                            + declaredHex + " — refusing to install");
        }
    }

    public PluginManifest install(Path archive) throws IOException {
        return install(archive, false);
    }

    public PluginManifest install(Path archive, boolean confirmPermissionEscalation) throws IOException {
        if (!Files.isRegularFile(archive) || !archive.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".fyp")) {
            throw new IllegalArgumentException("Expected a .fyp plugin package");
        }
        if (Files.size(archive) > MAX_PACKAGE_BYTES) throw new IllegalArgumentException("Plugin package exceeds 100 MB");
        // A matching `.fyp.sha256` sidecar is an INTEGRITY credential only: anyone
        // distributing a package can produce both files, so a locally installed package can
        // never claim official identity or the fan.summer.* namespace — those come from the
        // host-bundled seeder or an Ed25519 catalog signature authorized for that namespace.
        //
        // P1-5: the require-checksum policy applies on EVERY untrusted install path. A
        // PRESENT sidecar is always verified — a mismatch rejects the install even when
        // enforcement is off (a broken pin must never install silently).
        Path sidecarPath = Path.of(archive + ".sha256");
        boolean sidecarPresent = Files.isRegularFile(sidecarPath);
        if (sidecarPresent) {
            if (!verifySidecar(archive)) {
                throw new IllegalArgumentException(
                        "Checksum mismatch for " + archive.getFileName() + ": the .sha256 sidecar "
                                + "does not match the package — refusing to install");
            }
        } else if (fan.summer.fengyu.ai.service.AiConfigServiceHeadless.isMarketplaceChecksumRequired()) {
            throw new IllegalArgumentException(
                    "Checksum enforcement is enabled: " + archive.getFileName() + " must carry a "
                            + ".fyp.sha256 sidecar next to it");
        }
        String archiveSha256 = PluginIntegrityStore.sha256Hex(archive);
        try (InputStream input = Files.newInputStream(archive)) {
            return installArchive(input, false, archiveSha256, confirmPermissionEscalation);
        }
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
        String archiveSha256 = PluginIntegrityStore.sha256Hex(archive);
        PluginManifest installed;
        try (InputStream input = Files.newInputStream(archive)) {
            installed = installArchive(input, true, archiveSha256, true);
        }
        // Bundled packages are verified by the host-controlled checksum before reaching this
        // method and are seeded before plugin Workers start. They therefore have no runtime
        // preflight phase; finalize their transaction immediately so a later restart does not
        // mistake a successful official upgrade for an interrupted marketplace update.
        commitUpdate(installed.id());
        return installed;
    }

    /**
     * Read a package's manifest without installing it, so a caller can compare versions and decide
     * whether an upgrade is worthwhile (e.g. the official-plugin seeder) before paying the cost of
     * a full extract-and-replace. Only the {@code manifest.json} entry is parsed.
     */
    public PluginManifest readArchiveManifest(Path archive) throws IOException {
        if (!Files.isRegularFile(archive)) throw new IllegalArgumentException("Plugin package not found: " + archive);
        if (Files.size(archive) > MAX_PACKAGE_BYTES) throw new IllegalArgumentException("Plugin package exceeds 100 MB");
        try (InputStream input = Files.newInputStream(archive);
                ZipInputStream zip = new ZipInputStream(input)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if ("manifest.json".equals(entry.getName())) {
                    return readArchiveManifestEntry(zip, entry);
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
        if (file.isEmpty()) throw new IllegalArgumentException("Plugin package is empty");
        if (file.getSize() > MAX_PACKAGE_BYTES) throw new IllegalArgumentException("Plugin package exceeds 100 MB");
        try (InputStream input = file.getInputStream();
                ZipInputStream zip = new ZipInputStream(input)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if ("manifest.json".equals(entry.getName())) {
                    return readArchiveManifestEntry(zip, entry);
                }
            }
        }
        throw new IllegalArgumentException("manifest.json is missing in the uploaded package");
    }

    public PluginManifest installFromUrl(String url) throws IOException, InterruptedException {
        return installFromUrl(url, null);
    }

    /**
     * Installs a {@code .fyp} downloaded from {@code url}, optionally verified against
     * {@code expectedSha256} (the catalog entry's {@code sha256}).
     *
     * <p>Verification policy — every combination must fail closed:
     * <ul>
     *   <li>checksum enforcement ON: a digest must be supplied and match (the download
     *       itself satisfies the policy; no sidecar exists on this path).</li>
     *   <li>enforcement OFF, {@code https} URL: allowed (transport integrity), digest still
     *       verified when supplied.</li>
     *   <li>enforcement OFF, plain {@code http} URL: allowed only with a supplied digest —
     *       otherwise a network attacker could substitute the package bytes.</li>
     * </ul>
     */
    public PluginManifest installFromUrl(String url, String expectedSha256)
            throws IOException, InterruptedException {
        return installFromUrl(url, expectedSha256, null, null);
    }

    /** Download, pin, apply revocations, and optionally authenticate an Ed25519 publisher. */
    public PluginManifest installFromUrl(String url, String expectedSha256,
            String signature, String keyId) throws IOException, InterruptedException {
        return installFromUrl(url, expectedSha256, signature, keyId, false);
    }

    public PluginManifest installFromUrl(String url, String expectedSha256,
            String signature, String keyId, boolean confirmPermissionEscalation)
            throws IOException, InterruptedException {
        URI uri = URI.create(url);
        if (!List.of("https", "http").contains(uri.getScheme())) {
            throw new IllegalArgumentException("Plugin download URL must use HTTP(S)");
        }
        boolean digestSupplied = expectedSha256 != null && !expectedSha256.isBlank();
        boolean enforcementOn =
                fan.summer.fengyu.ai.service.AiConfigServiceHeadless.isMarketplaceChecksumRequired();
        if (enforcementOn && !digestSupplied) {
            throw new IllegalArgumentException(
                    "Checksum enforcement is enabled and the catalog entry carries no sha256 — "
                            + "download the .fyp with its .sha256 sidecar and install it locally");
        }
        if ("http".equals(uri.getScheme()) && !digestSupplied) {
            throw new IllegalArgumentException(
                    "Plain-http plugin downloads require a sha256 digest in the catalog entry "
                            + "(an unverified http download can be substituted on the wire)");
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2)).GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalArgumentException("Plugin download failed with HTTP " + response.statusCode());
        }
        long size = response.headers().firstValueAsLong("content-length").orElse(-1);
        if (size > MAX_PACKAGE_BYTES) throw new IllegalArgumentException("Plugin package exceeds 100 MB");
        // Every remote install is pinned to one temporary file: checksum, signature, manifest
        // inspection, revocation, and installation must all observe the exact same bytes.
        Path staging = Files.createTempFile("fengyu-plugin-download-", ".fyp");
        try {
            long total = 0;
            byte[] buffer = new byte[16 * 1024];
            try (InputStream body = response.body();
                    var out = Files.newOutputStream(staging)) {
                int count;
                while ((count = body.read(buffer)) >= 0) {
                    total += count;
                    if (total > MAX_PACKAGE_BYTES) {
                        throw new IllegalArgumentException("Plugin package exceeds 100 MB");
                    }
                    out.write(buffer, 0, count);
                }
            }
            String actual = PluginIntegrityStore.sha256Hex(staging);
            if (digestSupplied && !actual.equalsIgnoreCase(expectedSha256.trim())) {
                throw new IllegalArgumentException(
                        "SHA-256 mismatch for the downloaded plugin (catalog pins " + expectedSha256.trim()
                                + ", download hashes " + actual + ") — refusing to install");
            }
            PluginManifest manifest = readArchiveManifest(staging);
            PluginTrustStore.Verification verification;
            if (trustStore == null) {
                if ((signature != null && !signature.isBlank()) || (keyId != null && !keyId.isBlank())) {
                    throw new IllegalArgumentException(
                        "Plugin carries a signature but no publisher trust store is configured");
                }
                verification = new PluginTrustStore.Verification(false, null);
            } else {
                verification = trustStore.verify(staging, actual, manifest, signature, keyId);
            }
            try (InputStream input = Files.newInputStream(staging)) {
                return installArchive(input, verification.trusted(), actual,
                    confirmPermissionEscalation);
            }
        } finally {
            try {
                Files.deleteIfExists(staging);
            } catch (IOException ignored) {
                // Temp-file cleanup only.
            }
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
        Path rollback = rollbackRoot.resolve(id);
        if (Files.exists(rollback)) deleteTree(rollback);
        Files.deleteIfExists(transactionRoot.resolve(id + ".json"));
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
     *                      (the bundled official-plugin seeder, or an Ed25519-verified catalog
     *                      publisher authorized for the package namespace). {@code false} for
     *                      ordinary user uploads and unsigned downloads — these cannot claim
     *                      {@code official:true} or the reserved {@code fan.summer.*} namespace.
     */
    PluginManifest installArchive(InputStream input, boolean trustedSource) throws IOException {
        return installArchive(input, trustedSource, null, false);
    }

    private PluginManifest installArchive(InputStream input, boolean trustedSource,
            String sourceArchiveSha256) throws IOException {
        return installArchive(input, trustedSource, sourceArchiveSha256, false);
    }

    private PluginManifest installArchive(InputStream input, boolean trustedSource,
            String sourceArchiveSha256, boolean confirmPermissionEscalation) throws IOException {
        Files.createDirectories(root);
        Path staging = Files.createTempDirectory(root, ".install-");
        try {
            extract(input, staging);
            // Runtime state belongs to the host and cannot be smuggled in by a package.
            Files.deleteIfExists(staging.resolve(".disabled"));
            PluginManifest manifest = readManifest(staging);
            validate(manifest, staging, trustedSource);
            Path destination = pluginDir(manifest.id());
            ensurePermissionApproval(manifest, confirmPermissionEscalation);
            Path backup = rollbackRoot.resolve(manifest.id());
            Path journal = transactionRoot.resolve(manifest.id() + ".json");
            boolean updatingExisting = Files.isDirectory(destination);
            boolean wasEnabled = !Files.exists(destination.resolve(".disabled"));
            if (updatingExisting) {
                Files.createDirectories(rollbackRoot);
                Files.createDirectories(transactionRoot);
                if (Files.exists(backup)) deleteTree(backup);
                writeTransaction(journal, new UpdateTransaction(manifest.id(), backup.toString()));
                Files.move(destination, backup, StandardCopyOption.ATOMIC_MOVE);
            }
            try {
                Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
                if (!wasEnabled) Files.createFile(destination.resolve(".disabled"));
            } catch (IOException e) {
                if (Files.exists(backup) && !Files.exists(destination)) {
                    Files.move(backup, destination, StandardCopyOption.ATOMIC_MOVE);
                }
                Files.deleteIfExists(journal);
                throw e;
            }
            // Record the installed manifest's digest so the host can detect a runtime tamper of
            // manifest.json (a Worker must not be able to rewrite its own manifest and escalate).
            // P0-2: recorded only after the atomic swap succeeds, so a failed install leaves no
            // record that could later mask a tampered package. P0-6: the package directory digest
            // is also recorded so the Worker cache can key on content (a same-version repack with
            // different bytes gets a different digest → the stale Worker is invalidated).
            if (integrityStore != null) {
                integrityStore.record(manifest.id(), manifest.version(), destination.resolve("manifest.json"),
                        destination, sourceArchiveSha256);
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

    private void ensurePermissionApproval(PluginManifest incoming, boolean confirmed) {
        PluginManifest installed = find(incoming.id()).orElse(null);
        if (installed == null) return;
        java.util.Set<String> previous = new java.util.LinkedHashSet<>(
            Optional.ofNullable(installed.permissions()).orElse(List.of()));
        List<String> added = Optional.ofNullable(incoming.permissions()).orElse(List.of()).stream()
            .filter(permission -> !previous.contains(permission)).toList();
        if (!added.isEmpty() && !confirmed) {
            throw new IllegalArgumentException("Plugin update adds permissions " + added
                + "; inspect the package and explicitly confirm the permission escalation");
        }
    }

    /** Commit a health-checked update and remove its rollback snapshot. */
    public void commitUpdate(String id) throws IOException {
        Path backup = rollbackRoot.resolve(id);
        if (Files.exists(backup)) deleteTree(backup);
        Files.deleteIfExists(transactionRoot.resolve(id + ".json"));
    }

    /** Restore the last package snapshot after a failed startup/handshake. */
    public PluginManifest rollbackUpdate(String id) throws IOException {
        Path destination = pluginDir(id);
        Path backup = rollbackRoot.resolve(id);
        if (!Files.isDirectory(backup)) {
            throw new IllegalStateException("No rollback snapshot exists for plugin " + id);
        }
        Path failed = rollbackRoot.resolve(".failed-" + id);
        if (Files.exists(failed)) deleteTree(failed);
        if (Files.exists(destination)) Files.move(destination, failed, StandardCopyOption.ATOMIC_MOVE);
        Files.move(backup, destination, StandardCopyOption.ATOMIC_MOVE);
        if (Files.exists(failed)) deleteTree(failed);
        Files.deleteIfExists(transactionRoot.resolve(id + ".json"));
        PluginManifest restored = readManifest(destination);
        if (integrityStore != null) {
            integrityStore.record(id, restored.version(), destination.resolve("manifest.json"), destination);
        }
        return restored;
    }

    private void recoverInterruptedUpdates() {
        if (!Files.isDirectory(transactionRoot)) return;
        try (var journals = Files.list(transactionRoot)) {
            for (Path journal : journals.filter(Files::isRegularFile).toList()) {
                UpdateTransaction transaction = json.readValue(journal.toFile(), UpdateTransaction.class);
                Path backup = Path.of(transaction.backup()).toAbsolutePath().normalize();
                if (!backup.startsWith(rollbackRoot)) {
                    throw new IOException("Invalid plugin update journal backup path");
                }
                Path destination = pluginDir(transaction.id());
                if (Files.isDirectory(backup)) {
                    if (Files.exists(destination)) deleteTree(destination);
                    Files.move(backup, destination, StandardCopyOption.ATOMIC_MOVE);
                    recoveredUpdates.add(transaction.id());
                }
                Files.deleteIfExists(journal);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot recover interrupted plugin update", e);
        }
    }

    private void recordRecoveredIntegrity() {
        if (integrityStore == null || recoveredUpdates.isEmpty()) return;
        for (String id : List.copyOf(recoveredUpdates)) {
            Path destination = pluginDir(id);
            try {
                PluginManifest restored = readManifest(destination);
                integrityStore.record(id, restored.version(), destination.resolve("manifest.json"), destination);
                recoveredUpdates.remove(id);
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("Cannot restore integrity baseline for recovered plugin " + id, e);
            }
        }
    }

    private void writeTransaction(Path journal, UpdateTransaction transaction) throws IOException {
        Path temporary = Files.createTempFile(transactionRoot, ".update-", ".tmp");
        try {
            Files.writeString(temporary, json.writeValueAsString(transaction));
            Files.move(temporary, journal, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private record UpdateTransaction(String id, String backup) {}

    private void extract(InputStream input, Path staging) throws IOException {
        long total = 0;
        int entries = 0;
        byte[] buffer = new byte[16 * 1024];
        try (ZipInputStream zip = new ZipInputStream(input)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                // Entry-count cap: the expanded-bytes cap cannot see zero-byte entries, and a
                // zip of millions of empty entries would exhaust inodes long before bytes.
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
        if (Files.size(path) > MAX_MANIFEST_BYTES) throw new IllegalArgumentException("manifest.json exceeds 1 MB");
        return json.readValue(path.toFile(), PluginManifest.class);
    }

    private PluginManifest readArchiveManifestEntry(ZipInputStream zip, ZipEntry entry) throws IOException {
        if (entry.getSize() > MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException("manifest.json exceeds 1 MB");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(
            (int) Math.min(Math.max(0, entry.getSize()), 16 * 1024));
        byte[] buffer = new byte[8 * 1024];
        int total = 0;
        for (int count; (count = zip.read(buffer)) != -1;) {
            total += count;
            if (total > MAX_MANIFEST_BYTES) {
                throw new IllegalArgumentException("manifest.json exceeds 1 MB");
            }
            bytes.write(buffer, 0, count);
        }
        return json.readValue(bytes.toByteArray(), PluginManifest.class);
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
        // seeder, or an Ed25519 catalog publisher key authorized for its namespace) is rejected, so
        // no third party can masquerade as an official plugin or squat the official namespace.
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
        if (!SemanticVersion.isValid(m.version())) {
            throw new IllegalArgumentException("Plugin version must be semantic versioning");
        }
        if (m.engines() != null) {
            if (!SemanticVersionRange.isValid(m.engines().fengyu())) {
                throw new IllegalArgumentException("engines.fengyu must be a valid SemVer range");
            }
            PluginHostVersion.requireCompatible(m.engines().fengyu());
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
            String runtime = m.backend().runtime() == null ? "java" : m.backend().runtime();
            if (!java.util.Set.of("java", "python", "go").contains(runtime)) {
                throw new IllegalArgumentException("backend.runtime must be java, python, or go");
            }
            if (m.backend().protocolVersion() != null
                    && m.backend().protocolVersion() != PluginWorkerProtocol.PUBLIC_PROTOCOL_VERSION) {
                throw new IllegalArgumentException("Unsupported backend.protocolVersion: "
                    + m.backend().protocolVersion());
            }
            validateTimeout(m.backend().callTimeoutSeconds(), "backend.callTimeoutSeconds");
            if (m.backend().resources() != null) {
                Long memoryMb = m.backend().resources().memoryMb();
                Integer maxProcesses = m.backend().resources().maxProcesses();
                if (memoryMb != null && (memoryMb < 64 || memoryMb > 8192)) {
                    throw new IllegalArgumentException("backend.resources.memoryMb must be between 64 and 8192");
                }
                if (maxProcesses != null && (maxProcesses < 1 || maxProcesses > 64)) {
                    throw new IllegalArgumentException("backend.resources.maxProcesses must be between 1 and 64");
                }
            }
            Path worker = staging.resolve(workerArtifact(runtime)).normalize();
            if (!worker.startsWith(staging) || !Files.isRegularFile(worker)) {
                throw new IllegalArgumentException("Plugin backend artifact does not exist: "
                    + workerArtifact(runtime));
            }
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

    private static String workerArtifact(String runtime) {
        return switch (runtime) {
            case "python" -> "backend/worker.py";
            case "go" -> System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "backend/worker.exe" : "backend/worker";
            default -> "backend/worker.jar";
        };
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
     * sidecar is the CLI packager's integrity credential (GNU coreutils {@code sha256sum -c}
     * format: {@code <hex>  <basename>}). It detects corruption but does not grant official identity
     * or a reserved namespace: local installs remain untrusted because anyone can replace both the
     * archive and sidecar. Remote authenticity uses {@link PluginTrustStore} Ed25519 publisher keys;
     * the bundled seeder has its own host-controlled trust path.
     *
     * <p>Returns {@code false} (never throws) when the sidecar is absent or mismatched.
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
