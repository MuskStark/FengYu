package fan.summer.fengyu.plugin.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Stream;

/**
 * Installs official .fyp artifacts, upgrades newer versions, and refreshes changed same-version bundles.
 *
 * <p>Seeding runs in the BACKGROUND (a daemon thread dispatched by the {@link ApplicationRunner}):
 * digesting and extracting every bundled archive used to sit on the Spring startup thread — and,
 * before that, inside bean initialization via the tool registry — so a handful of large first-install
 * archives directly delayed first paint. The install surface exposes progress
 * ({@link #progress()} / {@link #seedingDone()}) so the UI can show an installing state instead of a
 * silently short plugin list. Full verification is retained: every archive still must carry a
 * matching SHA-256 sidecar before it is trusted.</p>
 */
@Component
public class OfficialPluginSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(OfficialPluginSeeder.class);
    private final PluginPackageService packages;
    private final Path source;
    /** Runs the seed off the startup thread; single-threaded so installs never race each other. */
    private final ExecutorService seedExecutor;
    /** Per-plugin seeding state for the status surface (id → latest state); safe for concurrent reads. */
    private final Map<String, SeedProgress> progress = new ConcurrentHashMap<>();
    private volatile boolean seedingDone = false;

    public OfficialPluginSeeder(PluginPackageService packages,
            @Value("${fengyu.plugins.official-directory:${user.dir}/OfficialPlugins/target/packages}") String source) {
        this.packages = packages; this.source = Path.of(source).toAbsolutePath().normalize();
        this.seedExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread worker = new Thread(task, "official-plugin-seeder");
            worker.setDaemon(true);
            return worker;
        });
    }

    /** Install state of one bundled official plugin: {@code installing|ready|failed|skipped}. */
    public record SeedProgress(String id, String state, String error) {}

    /**
     * Dispatches the background seed. Synchronous callers (tests, explicit re-seeds) use
     * {@link #seed()} directly; this runner path must not block application startup.
     */
    @Override public void run(ApplicationArguments args) {
        try {
            seedExecutor.execute(() -> {
                try {
                    seed();
                } finally {
                    seedingDone = true;
                }
            });
        } catch (RejectedExecutionException shuttingDown) {
            seedingDone = true;
        }
    }

    /** Snapshot of the seeding states, in first-seen order; empty once nothing has been seeded. */
    public List<SeedProgress> progress() {
        return List.copyOf(progress.values());
    }

    /** True once the background seed pass finished (or was never dispatched in this process). */
    public boolean seedingDone() {
        return seedingDone;
    }

    @PreDestroy public void shutdown() {
        seedExecutor.shutdownNow();
    }

    public synchronized void seed() {
        if (!Files.isDirectory(source)) {
            seedingDone = true;
            return;
        }
        try (var entries = Files.list(source)) {
            for (Path archive : highestVersionArchivePerId(entries)) {
                seedArchive(archive);
            }
        } catch (Exception e) {
            log.warn("Cannot scan official plugin packages: {}", e.getMessage());
        }
    }

    /**
     * From the directory listing keep only the highest-version {@code .fyp} archive per plugin id.
     * Without this dedupe, multiple same-id archives (an old build left beside a new one) would each
     * be (re)installed in filesystem listing order, so the installed version would depend on that
     * non-deterministic order instead of on the archives' own versions — an upgrade/downgrade that
     * passed on one OS and failed on another.
     */
    List<Path> highestVersionArchivePerId(Stream<Path> entries) {
        record Candidate(Path archive, String version) {}
        Map<String, Candidate> best = new LinkedHashMap<>();
        for (Path archive : entries.filter(p -> p.getFileName().toString().endsWith(".fyp")).toList()) {
            try {
                String id = archive.getFileName().toString().replaceFirst("-\\d+\\.\\d+\\.\\d+.*\\.fyp$", "");
                String version = packages.readArchiveManifest(archive).version();
                Candidate prior = best.get(id);
                if (prior == null || SemanticVersion.compare(version, prior.version()) > 0) {
                    best.put(id, new Candidate(archive, version));
                }
            } catch (Exception e) {
                log.warn("Cannot read official plugin archive {}: {}", archive, e.getMessage());
            }
        }
        return best.values().stream().map(Candidate::archive).toList();
    }

    private void seedArchive(Path archive) {
        String id = archive.getFileName().toString().replaceFirst("-\\d+\\.\\d+\\.\\d+.*\\.fyp$", "");
        try {
            // Honour a user uninstall: a tombstone is written on uninstall so the seeder can
            // distinguish a user-removed plugin (skip) from a never-installed one (seed). This
            // check MUST precede the lacksRecord reinstall block below — otherwise a plugin
            // whose package dir + integrity record were both deleted by uninstall would be
            // reinstalled as if brand new. A later reinstall clears the tombstone.
            if (packages.integrityStore() != null && packages.integrityStore().isUninstalled(id)) {
                log.info("Skipping official plugin {}: uninstalled by user", id);
                progress.put(id, new SeedProgress(id, "skipped", null));
                return;
            }
            // P0-8: every bundled official plugin MUST ship a matching checksum sidecar.
            // Verify before version/content decisions so an untrusted archive never
            // influences the installed package selection.
            Path checksum = Path.of(archive + ".sha256");
            if (!Files.exists(checksum)) {
                log.warn("Skipping official plugin {}: missing required .sha256 sidecar (official packages must be checksummed)", archive);
                progress.put(id, new SeedProgress(id, "skipped", "missing .sha256 sidecar"));
                return;
            }
            // ONE full-file digest per archive: it verifies the sidecar, feeds the same-version
            // refresh check, and is handed to the trusted install. The previous flow hashed the
            // same bytes three times (sidecar verify + refresh compare + integrity record).
            String incomingSha256 = sha256Hex(archive);
            if (!matchesSidecar(incomingSha256, Files.readString(checksum).trim())) {
                log.warn("Skipping official plugin {}: SHA256 checksum mismatch (package tampered or corrupt)", archive);
                progress.put(id, new SeedProgress(id, "skipped", "SHA256 checksum mismatch"));
                return;
            }
            PluginManifest incoming = packages.readArchiveManifest(archive);
            PluginManifest installed = packages.find(id).orElse(null);
            if (installed != null) {
                // P0-2 trusted-reinstall migration: an installed official plugin that has NO
                // integrity record predates the integrity store (it was installed by an older
                // host that may have let a Worker write its own install dir). Do NOT endorse the
                // current on-disk state — reinstall from this trusted bundled archive (SHA-256
                // sidecar verified above) so a fresh, trusted baseline record is established.
                // This re-runs even when the bundled version equals/older the installed one;
                // the normal upgrade path below still handles strictly-newer bundles.
                boolean lacksRecord = packages.integrityStore() == null
                        || packages.integrityStore().read(id).isEmpty();
                if (lacksRecord) {
                    log.info("Reinstalling official plugin {} from bundled archive to establish a trusted integrity baseline", id);
                } else {
                    int comparison = SemanticVersion.compare(
                            incoming.version(), installed.version());
                    if (comparison < 0) {
                        progress.put(id, new SeedProgress(id, "skipped", null));
                        return; // never downgrade
                    }
                    if (comparison == 0) {
                        String recordedSource = packages.integrityStore()
                                .sourceArchiveSha256(id).orElse(null);
                        if (recordedSource != null
                                && incomingSha256.equalsIgnoreCase(recordedSource)) {
                            progress.put(id, new SeedProgress(id, "ready", null));
                            return; // same version and identical trusted source bytes
                        }
                        log.info("Refreshing official plugin {} {} from changed bundled archive",
                                id, installed.version());
                    } else {
                        log.info("Upgrading official plugin {} {} → {}", id,
                                installed.version(), incoming.version());
                    }
                }
            }
            progress.put(id, new SeedProgress(id, "installing", null));
            // The package service performs the authoritative validation and atomic install.
            // installTrusted marks this as a host-trusted path so the package may legitimately
            // declare official:true / use the fan.summer.* namespace. Trust comes from this
            // host-controlled bundled path; the sidecar verifies pair consistency. User uploads
            // cannot claim either identity property (P0-8).
            packages.installTrusted(archive, incomingSha256);
            progress.put(id, new SeedProgress(id, "ready", null));
            log.info("Official plugin ready: {} {}", incoming.id(), incoming.version());
        } catch (Exception e) {
            progress.put(id, new SeedProgress(id, "failed", e.getMessage()));
            log.warn("Cannot seed official plugin {}: {}", id, e.getMessage());
        }
    }

    /**
     * Compare a precomputed archive digest against the {@code <hex>  <basename>} line in
     * {@code checksum}. Tolerates the GNU coreutils binary-mode ({@code *}) and text-mode
     * prefixes. Returns {@code false} (never throws) when verification fails.
     */
    static boolean matchesSidecar(String actualHex, String checksumLine) {
        String expected = parseExpectedHash(checksumLine);
        return expected != null && expected.equalsIgnoreCase(actualHex);
    }

    /**
     * Compare the archive's actual SHA256 against its sidecar — convenience wrapper computing
     * the digest first; the seeding path uses {@link #matchesSidecar} with its shared digest.
     */
    static boolean verifySha256(Path archive, Path checksum) throws IOException {
        return matchesSidecar(sha256Hex(archive), Files.readString(checksum).trim());
    }

    private static String parseExpectedHash(String line) {
        // Format: "<hex>  <filename>" or "<hex> *<filename>". The hex is the first whitespace token.
        for (int i = 0; i < line.length(); i++) {
            if (Character.isWhitespace(line.charAt(i))) {
                return line.substring(0, i);
            }
        }
        return line.isEmpty() ? null : line;
    }

    static String sha256Hex(Path file) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int count;
            while ((count = in.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return hex.toString();
    }
}
