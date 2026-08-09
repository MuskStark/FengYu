package fan.summer.fengyu.plugin.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.runtime.RuntimePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

/**
 * Host-owned record of each installed plugin's {@code manifest.json} digest, used to detect (and
 * block) a plugin rewriting its own manifest at runtime to escalate permissions.
 *
 * <p>On every successful install a record {@code {id, version, sha256(manifest.json), installedAt}}
 * is written under {@code <runtime-root>/manifest-digests/<id>.json}. Before a Worker starts, the
 * host recomputes the live manifest's SHA-256 and compares it to the stored record; a mismatch
 * means the on-disk package (which is now read-only to the Worker) was tampered with out-of-band,
 * and the Worker is refused.
 *
 * <p>The store is intentionally file-based and co-located with the other host runtime state (no DB
 * dependency) so it works in SETUP mode and in tests. Writes are atomic (temp + ATOMIC_MOVE) to
 * match the installer's own swap style.
 *
 * <p>This is integrity (tamper detection), not authenticity. Authenticity — proving a package was
 * produced by a trusted publisher — requires asymmetric signature verification and is a tracked
 * follow-up (see P0-8). Integrity still closes the self-escalation loop described in the Beta
 * readiness review: a plugin can no longer rewrite its manifest, restart, and have the host honor
 * the new (escalated) permissions.
 */
@Service
public class PluginIntegrityStore {
    private static final Logger log = LoggerFactory.getLogger(PluginIntegrityStore.class);

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private final Path root;

    public PluginIntegrityStore() {
        this(RuntimePaths.root().resolve("manifest-digests"));
    }

    /** Test-only constructor: pin the store to a specific directory. */
    public PluginIntegrityStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * Record the digest of an installed plugin's manifest AND the whole package directory. Called by
     * the installer after a successful atomic package swap. Overwrites any prior record for the id
     * (an upgrade replaces the old version's digests). The package digest is the Worker identity's
     * content key (P0-6): two builds of the same version with different bytes get different digests,
     * so a same-version repack invalidates the cached Worker.
     */
    public void record(String id, String version, Path manifestPath, Path packageDir) {
        try {
            String manifestDigest = sha256Hex(manifestPath);
            String packageDigest = packageDigest(packageDir);
            Path target = recordPath(id);
            Files.createDirectories(target.getParent());
            Entry entry = new Entry(id, version, manifestDigest, packageDigest, java.time.Instant.now().toString());
            Path tmp = Files.createTempFile(target.getParent(), ".digest-", ".tmp");
            try {
                Files.writeString(tmp, json.writeValueAsString(entry));
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            // A failed record weakens tamper detection (the next start will see no record and fail
            // closed), but it must not break the install itself. Log and continue.
            log.warn("Cannot record integrity digest for plugin {}: {}", id, e.getMessage());
        }
    }

    /** Backwards-compatible record: manifest-only (no package digest). */
    public void record(String id, String version, Path manifestPath) {
        record(id, version, manifestPath, null);
    }

    /** The recorded package digest for a plugin, or empty if none was recorded. */
    public Optional<String> packageDigest(String id) {
        return read(id).map(Entry::packageDigest).filter(d -> d != null && !d.isBlank());
    }

    /**
     * Compute a stable SHA-256 over a package directory's contents: walk the tree, sort entry paths
     * ascending, and feed {@code <relativePath>\n<fileBytes>} for each regular file. {@code null}
     * dir returns {@code null} (manifest-only record, no package identity).
     */
    static String packageDigest(Path packageDir) throws IOException {
        if (packageDir == null) return null;
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        java.util.List<Path> files = new java.util.ArrayList<>();
        try (var walk = Files.walk(packageDir)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        files.sort(Comparator.comparing(p -> packageDir.relativize(p).toString()));
        byte[] newline = System.lineSeparator().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (Path file : files) {
            String rel = packageDir.relativize(file).toString();
            digest.update(rel.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(newline);
            try (java.io.InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) digest.update(buf, 0, n);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return hex.toString();
    }

    /**
     * Remove the record for an uninstalled plugin. Best-effort: a leftover record is harmless (the
     * package dir is gone, so no worker can start against it), but removing keeps the store clean.
     */
    public void forget(String id) {
        try {
            Files.deleteIfExists(recordPath(id));
        } catch (IOException e) {
            log.debug("Cannot remove integrity record for {}: {}", id, e.getMessage());
        }
    }

    /**
     * Verify that a live {@code manifest.json} matches the recorded digest for the plugin.
     *
     * @return {@code true} if a record exists AND the live manifest matches it; {@code false} if
     *         the manifest was tampered with. Empty when no record exists yet (a brand-new install
     *         that predates the store, or a legacy plugin) — callers may treat absence as "not
     *         enforced" but must NOT treat it as "verified".
     */
    public Optional<Boolean> verify(String id, Path manifestPath) {
        Optional<Entry> stored = read(id);
        if (stored.isEmpty()) return Optional.empty();
        try {
            String live = sha256Hex(manifestPath);
            return Optional.of(stored.get().sha256().equalsIgnoreCase(live));
        } catch (IOException e) {
            // Cannot read the live manifest to compare — treat as a verification failure (fail
            // closed) rather than silently letting a Worker start.
            log.warn("Cannot hash live manifest for plugin {}: {}", id, e.getMessage());
            return Optional.of(false);
        }
    }

    /**
     * Verify that the live installed package DIRECTORY matches the recorded whole-package digest.
     * This catches tampering of any file in the package (the Worker JAR, libs, assets) — not just
     * {@code manifest.json} — so a Worker whose JAR was rewritten out-of-band (with the manifest
     * left intact) is refused at start.
     *
     * @return {@code true} if a record with a package digest exists AND the live directory matches
     *         it; {@code false} on mismatch or read/hash failure (fail closed); empty when there is
     *         no record OR the record predates package digests (legacy manifest-only record) — the
     *         caller may treat that as "not enforced for the whole package" but must NOT treat it
     *         as verified.
     */
    public Optional<Boolean> verifyPackage(String id, Path packageDir) {
        Optional<Entry> stored = read(id);
        if (stored.isEmpty()) return Optional.empty();
        String recorded = stored.get().packageDigest();
        if (recorded == null || recorded.isBlank()) return Optional.empty();  // legacy record
        try {
            String live = packageDigest(packageDir);
            return Optional.of(recorded.equalsIgnoreCase(live));
        } catch (IOException e) {
            log.warn("Cannot hash live package directory for plugin {}: {}", id, e.getMessage());
            return Optional.of(false);
        }
    }

    /** Read the stored record for a plugin (if any). */
    public Optional<Entry> read(String id) {
        Path path = recordPath(id);
        if (!Files.isRegularFile(path)) return Optional.empty();
        try {
            return Optional.of(json.readValue(path.toFile(), Entry.class));
        } catch (IOException e) {
            log.warn("Cannot read integrity record for plugin {}: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    private Path recordPath(String id) {
        if (id == null || !id.matches("[a-z0-9]+(?:[.-][a-z0-9]+)+")) {
            throw new IllegalArgumentException("Invalid plugin id for integrity record");
        }
        return root.resolve(id + ".json");
    }

    /** Compute the SHA-256 hex digest of a file's bytes. */
    static String sha256Hex(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
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

    /**
     * Persisted integrity record for one plugin. {@code packageDigest} is the content key for the
     * Worker cache (P0-6); it may be {@code null} on records written before that field existed
     * (Jackson deserializes a missing field as null for a record's nullable component).
     */
    public record Entry(String id, String version, String sha256, String packageDigest, String installedAt) {
        /** Backwards-compatible constructor for records/manifest-only writes lacking a package digest. */
        public Entry(String id, String version, String sha256, String installedAt) {
            this(id, version, sha256, null, installedAt);
        }
    }
}
