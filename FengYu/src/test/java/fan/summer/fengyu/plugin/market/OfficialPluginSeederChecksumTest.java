package fan.summer.fengyu.plugin.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the SHA256 sidecar check performed by {@link OfficialPluginSeeder}. The host treats a
 * sidecar as required and authoritative (missing or mismatched → skip). These tests exercise
 * {@link OfficialPluginSeeder#verifySha256} directly so
 * they don't need to boot a plugin runtime.
 */
class OfficialPluginSeederChecksumTest {

    @TempDir Path temp;

    @Test
    void matchingChecksumVerifies() throws Exception {
        Path archive = writeArchive("pkg.fyp", "package bytes");
        Path sidecar = writeSidecar("pkg.fyp.sha256", sha256Hex(archive) + "  pkg.fyp\n");
        assertTrue(OfficialPluginSeeder.verifySha256(archive, sidecar));
    }

    @Test
    void mismatchedChecksumIsRejected() throws Exception {
        Path archive = writeArchive("pkg.fyp", "package bytes");
        Path sidecar = writeSidecar("pkg.fyp.sha256",
            "0000000000000000000000000000000000000000000000000000000000000000  pkg.fyp\n");
        assertFalse(OfficialPluginSeeder.verifySha256(archive, sidecar));
    }

    @Test
    void binaryModeCoreutilsFormatIsAccepted() throws Exception {
        Path archive = writeArchive("pkg.fyp", "package bytes");
        // GNU coreutils emits "<hex> *<basename>" in binary mode; the parser must accept it.
        Path sidecar = writeSidecar("pkg.fyp.sha256", sha256Hex(archive) + " *pkg.fyp\n");
        assertTrue(OfficialPluginSeeder.verifySha256(archive, sidecar));
    }

    private Path writeArchive(String name, String content) throws Exception {
        Path archive = temp.resolve(name);
        Files.writeString(archive, content);
        return archive;
    }

    private Path writeSidecar(String name, String content) throws Exception {
        Path sidecar = temp.resolve(name);
        Files.writeString(sidecar, content);
        return sidecar;
    }

    private static String sha256Hex(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
        }
        byte[] hash = md.digest();
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return hex.toString();
    }
}
