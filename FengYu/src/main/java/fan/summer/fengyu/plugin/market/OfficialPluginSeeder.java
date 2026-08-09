package fan.summer.fengyu.plugin.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/** Installs bundled/development official .fyp artifacts once and upgrades them when newer. */
@Component
public class OfficialPluginSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(OfficialPluginSeeder.class);
    private final PluginPackageService packages;
    private final Path source;

    public OfficialPluginSeeder(PluginPackageService packages,
            @Value("${fengyu.plugins.official-directory:${user.dir}/OfficialPlugins/target/packages}") String source) {
        this.packages = packages; this.source = Path.of(source).toAbsolutePath().normalize();
    }

    @Override public void run(ApplicationArguments args) {
        seed();
    }

    public synchronized void seed() {
        if (!Files.isDirectory(source)) return;
        try (var entries = Files.list(source)) {
            for (Path archive : entries.filter(p -> p.getFileName().toString().endsWith(".fyp")).toList()) {
                try {
                    String id = archive.getFileName().toString().replaceFirst("-\\d+\\.\\d+\\.\\d+.*\\.fyp$", "");
                    PluginManifest incoming = packages.readArchiveManifest(archive);
                    PluginManifest installed = packages.find(id).orElse(null);
                    if (installed != null) {
                        // P0-2 trusted-reinstall migration: an installed official plugin that has NO
                        // integrity record predates the integrity store (it was installed by an older
                        // host that may have let a Worker write its own install dir). Do NOT endorse the
                        // current on-disk state — reinstall from this trusted bundled archive (SHA-256
                        // sidecar verified below) so a fresh, trusted baseline record is established.
                        // This re-runs even when the bundled version equals/older the installed one;
                        // the normal upgrade path below still handles strictly-newer bundles.
                        boolean lacksRecord = packages.integrityStore() == null
                                || packages.integrityStore().read(id).isEmpty();
                        if (lacksRecord) {
                            log.info("Reinstalling official plugin {} from bundled archive to establish a trusted integrity baseline", id);
                        } else if (PluginMarketplaceService.compareVersions(incoming.version(), installed.version()) <= 0) {
                            // Upgrade only when the bundled archive is strictly newer; never downgrade.
                            // This honours the class Javadoc ("upgrades them when newer") and is what
                            // lets a rebuilt worker JAR reach a user who already has the plugin installed.
                            continue;
                        } else {
                            log.info("Upgrading official plugin {} {} → {}", id, installed.version(), incoming.version());
                        }
                    }
                    // P0-8: every bundled official plugin MUST ship a `.sha256` sidecar, and it MUST
                    // match. The seeder is a host-controlled bundled-package path; the sidecar is a
                    // corruption/partial-release check, not an independent authenticity anchor (an
                    // attacker able to replace both files can still make them agree). Code signing or
                    // an asymmetric package signature remains the distribution-level authenticity
                    // boundary. Missing/mismatching pairs fail closed so incomplete or corrupted
                    // releases can never acquire official identity.
                    Path checksum = Path.of(archive + ".sha256");
                    if (!Files.exists(checksum)) {
                        log.warn("Skipping official plugin {}: missing required .sha256 sidecar (official packages must be checksummed)", archive);
                        continue;
                    }
                    if (!verifySha256(archive, checksum)) {
                        log.warn("Skipping official plugin {}: SHA256 checksum mismatch (package tampered or corrupt)", archive);
                        continue;
                    }
                    // The package service performs the authoritative validation and atomic install.
                    // installTrusted marks this as a host-trusted path so the package may legitimately
                    // declare official:true / use the fan.summer.* namespace. Trust comes from this
                    // host-controlled bundled path; the sidecar verifies pair consistency. User uploads
                    // cannot claim either identity property (P0-8).
                    packages.installTrusted(archive);
                    log.info("Official plugin ready: {} {}", incoming.id(), incoming.version());
                } catch (Exception e) {
                    log.warn("Cannot seed official plugin {}: {}", archive, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Cannot scan official plugin packages: {}", e.getMessage());
        }
    }

    /**
     * Compare the archive's actual SHA256 against the {@code <hex>  <basename>} line in
     * {@code checksum}. Tolerates the GNU coreutils binary-mode ({@code *}) and text-mode
     * prefixes. Returns {@code false} (never throws) when verification fails.
     */
    static boolean verifySha256(Path archive, Path checksum) throws IOException {
        String expected = parseExpectedHash(Files.readString(checksum).trim());
        if (expected == null) return false;
        String actual = sha256Hex(archive);
        return expected.equalsIgnoreCase(actual);
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

    private static String sha256Hex(Path file) throws IOException {
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
