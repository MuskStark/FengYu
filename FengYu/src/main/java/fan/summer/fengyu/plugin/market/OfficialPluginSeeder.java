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

    @Override public void run(ApplicationArguments args) { seed(); }

    public synchronized void seed() {
        if (!Files.isDirectory(source)) return;
        try (var entries = Files.list(source)) {
            for (Path archive : entries.filter(p -> p.getFileName().toString().endsWith(".fyp")).toList()) {
                try {
                    String id = archive.getFileName().toString().replaceFirst("-\\d+\\.\\d+\\.\\d+.*\\.fyp$", "");
                    if (packages.find(id).isPresent()) continue;
                    // When a .sha256 sidecar ships alongside the package, verify integrity BEFORE
                    // handing the archive to the installer. This catches bit-rot and transport
                    // corruption without blocking dev workflows that copy packages by hand (no
                    // sidecar → treated as backwards-compatible, install proceeds).
                    Path checksum = Path.of(archive + ".sha256");
                    if (Files.exists(checksum) && !verifySha256(archive, checksum)) {
                        log.warn("Skipping official plugin {}: SHA256 checksum mismatch", archive);
                        continue;
                    }
                    // The package service performs the authoritative validation and atomic install.
                    PluginManifest incoming = packages.install(archive);
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
