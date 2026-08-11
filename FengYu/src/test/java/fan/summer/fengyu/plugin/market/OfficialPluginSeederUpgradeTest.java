package fan.summer.fengyu.plugin.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: {@link OfficialPluginSeeder} must UPGRADE an already-installed official plugin when
 * the bundled {@code .fyp} carries a newer version. Previously it skipped any id already present
 * ({@code if (packages.find(id).isPresent()) continue}) with no version check — so a rebuilt
 * worker JAR (e.g. a logging fix) never reached a user who had an older plugin installed. The
 * class Javadoc already promised "upgrades them when newer"; these tests pin that behaviour.
 */
class OfficialPluginSeederUpgradeTest {

    @TempDir Path temp;

    @Test
    void upgradesWhenBundledVersionIsNewer() throws Exception {
        Path packagesDir = temp.resolve("packages");
        Path installDir = temp.resolve("installed");
        Files.createDirectories(packagesDir);
        PluginPackageService service = new PluginPackageService(installDir.toString());

        // Seed once with an older archive → installs 1.0.0.
        writeArchive(packagesDir, "fan.summer.demo-1.0.0.fyp", "1.0.0");
        OfficialPluginSeeder seeder = new OfficialPluginSeeder(service, packagesDir.toString());
        seeder.seed();
        assertEquals("1.0.0", service.find("fan.summer.demo").orElseThrow().version());

        // Drop a NEWER archive next to it and re-seed → must upgrade to 2.0.0.
        writeArchive(packagesDir, "fan.summer.demo-2.0.0.fyp", "2.0.0");
        seeder.seed();
        assertEquals("2.0.0", service.find("fan.summer.demo").orElseThrow().version());
    }

    @Test
    void doesNotDowngradeWhenBundledVersionIsOlder() throws Exception {
        Path packagesDir = temp.resolve("packages");
        Path installDir = temp.resolve("installed");
        Files.createDirectories(packagesDir);
        PluginPackageService service = new PluginPackageService(installDir.toString());
        writeArchive(packagesDir, "fan.summer.demo-2.0.0.fyp", "2.0.0");
        OfficialPluginSeeder seeder = new OfficialPluginSeeder(service, packagesDir.toString());
        seeder.seed();
        assertEquals("2.0.0", service.find("fan.summer.demo").orElseThrow().version());

        // An OLDER archive must NOT downgrade the already-installed newer plugin.
        writeArchive(packagesDir, "fan.summer.demo-1.0.0.fyp", "1.0.0");
        seeder.seed();
        assertEquals("2.0.0", service.find("fan.summer.demo").orElseThrow().version());
    }

    /**
     * Regression (P0-8): the seeder is the trusted path that grants a package the right to declare
     * {@code official:true} / use {@code fan.summer.*}. A bundled official package without a
     * matching {@code .sha256} sidecar MUST be refused (fail closed) rather than installed as a
     * trusted official plugin. Without this, any package dropped into the packages dir would gain
     * official identity.
     */
    @Test
    void refusesToSeedOfficialPluginWithoutSha256Sidecar() throws Exception {
        Path packagesDir = temp.resolve("packages");
        Path installDir = temp.resolve("installed");
        Files.createDirectories(packagesDir);
        PluginPackageService service = new PluginPackageService(installDir.toString());

        writeArchiveWithoutSidecar(packagesDir, "fan.summer.demo-1.0.0.fyp", "1.0.0");
        OfficialPluginSeeder seeder = new OfficialPluginSeeder(service, packagesDir.toString());
        seeder.seed();

        // No sidecar → fail closed: the plugin must NOT be installed as a trusted official package.
        assertTrue(service.find("fan.summer.demo").isEmpty(),
            "seeder must refuse an official package with no .sha256 sidecar");
    }

    /**
     * Regression (P0-2 trusted-reinstall migration): an already-installed OFFICIAL plugin that has
     * NO integrity record (it predates the integrity store — installed by an older host that may
     * have let a Worker write its own install dir) must NOT have its current on-disk state endorsed
     * as a trusted baseline. Instead the seeder reinstalls it from the trusted bundled archive
     * (SHA-256 sidecar verified), which establishes a fresh record against trusted bytes. The
     * reinstall fires even when the bundled version equals the installed one.
     */
    @Test
    void reinstallsOfficialPluginWithoutIntegrityRecordFromBundledArchive() throws Exception {
        Path packagesDir = temp.resolve("packages");
        Path installDir = temp.resolve("installed");
        Path digestsDir = temp.resolve("digests");
        Files.createDirectories(packagesDir);
        PluginPackageService service = new PluginPackageService(installDir.toString());
        PluginIntegrityStore integrity = new PluginIntegrityStore(digestsDir);
        service.attachIntegrityStoreForTest(integrity);

        // Install once via the trusted seeder → establishes a record.
        writeArchive(packagesDir, "fan.summer.demo-1.0.0.fyp", "1.0.0");
        OfficialPluginSeeder seeder = new OfficialPluginSeeder(service, packagesDir.toString());
        seeder.seed();
        assertTrue(integrity.read("fan.summer.demo").isPresent(), "first seed establishes a record");

        // Simulate a legacy install: drop the record (the plugin is installed but unverified).
        integrity.forget("fan.summer.demo");
        assertTrue(integrity.read("fan.summer.demo").isEmpty());

        // Re-seed: the plugin lacks a record, so the seeder reinstalls from the bundled archive and
        // re-establishes a trusted baseline record (NOT by endorsing the current on-disk state).
        seeder.seed();
        assertTrue(integrity.read("fan.summer.demo").isPresent(),
            "seeder must reinstall a record-less official plugin from the bundled archive to establish a trusted baseline");
    }

    /**
     * Regression: the seeder must NOT re-seed an official plugin the user uninstalled. Previously
     * uninstall deleted both the package dir and the integrity record, leaving no trace; on the next
     * restart the seeder could not distinguish "user uninstalled" from "never installed" and
     * reinstalled the bundled archive. The fix is an uninstall tombstone the seeder checks before
     * its lacksRecord-reinstall block.
     */
    @Test
    void doesNotReseedUserUninstalledPlugin() throws Exception {
        Path packagesDir = temp.resolve("packages");
        Path installDir = temp.resolve("installed");
        Path digestsDir = temp.resolve("digests");
        Files.createDirectories(packagesDir);
        PluginPackageService service = new PluginPackageService(installDir.toString());
        PluginIntegrityStore integrity = new PluginIntegrityStore(digestsDir);
        service.attachIntegrityStoreForTest(integrity);

        writeArchive(packagesDir, "fan.summer.demo-1.0.0.fyp", "1.0.0");
        OfficialPluginSeeder seeder = new OfficialPluginSeeder(service, packagesDir.toString());
        seeder.seed();
        assertTrue(service.find("fan.summer.demo").isPresent(), "first seed installs the plugin");

        // User uninstalls: package dir + integrity record deleted, tombstone written.
        service.uninstall("fan.summer.demo", false);
        assertTrue(service.find("fan.summer.demo").isEmpty(), "uninstall removes the package dir");
        assertTrue(integrity.isUninstalled("fan.summer.demo"), "uninstall writes a tombstone");

        // Restart (re-seed): the seeder MUST skip the tombstoned plugin instead of reinstalling it.
        seeder.seed();
        assertTrue(service.find("fan.summer.demo").isEmpty(),
            "seeder must not reseed a plugin the user uninstalled");
    }

    /**
     * Regression: a reinstall after uninstall must clear the tombstone so the seeder's normal
     * upgrade path resumes. Otherwise a user who uninstalled, then changed their mind and installed
     * the plugin back, would find it reseeded-then-immediately-suppressed forever (the tombstone
     * would survive and suppress all future re-seeds even though the plugin is installed again).
     */
    @Test
    void reinstallClearsTombstoneSoFutureUninstallIsHonoured() throws Exception {
        Path packagesDir = temp.resolve("packages");
        Path installDir = temp.resolve("installed");
        Path digestsDir = temp.resolve("digests");
        Files.createDirectories(packagesDir);
        PluginPackageService service = new PluginPackageService(installDir.toString());
        PluginIntegrityStore integrity = new PluginIntegrityStore(digestsDir);
        service.attachIntegrityStoreForTest(integrity);

        writeArchive(packagesDir, "fan.summer.demo-1.0.0.fyp", "1.0.0");
        OfficialPluginSeeder seeder = new OfficialPluginSeeder(service, packagesDir.toString());
        seeder.seed();
        service.uninstall("fan.summer.demo", false);
        assertTrue(integrity.isUninstalled("fan.summer.demo"));

        // Simulate the user explicitly reinstalling (e.g. a newer bundled version drops, or they
        // clear their intent): the tombstone is cleared and the seeder resumes seeding.
        integrity.clearUninstalled("fan.summer.demo");
        assertFalse(integrity.isUninstalled("fan.summer.demo"));
        seeder.seed();
        assertTrue(service.find("fan.summer.demo").isPresent(), "plugin reinstalled after tombstone cleared");
        // After the reinstall, a fresh uninstall must write a new tombstone (i.e. the cycle works).
        service.uninstall("fan.summer.demo", false);
        assertTrue(integrity.isUninstalled("fan.summer.demo"), "a fresh uninstall re-tombstones");
    }

    /** As {@link #writeArchive} but omits the {@code .sha256} sidecar (for the fail-closed test). */
    private void writeArchiveWithoutSidecar(Path dir, String name, String version) throws Exception {
        String manifest = """
            {"schemaVersion":2,"id":"fan.summer.demo","name":"Demo","description":"Demo plugin",
             "version":"%s","author":"Example","icon":"puzzle-outline","category":"dev",
             "ui":{"entry":"ui/index.html"},"permissions":["files.read"]}
            """.formatted(version);
        Path archive = dir.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("ui/index.html"));
            zip.write("<html></html>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private void writeArchive(Path dir, String name, String version) throws Exception {
        String manifest = """
            {"schemaVersion":2,"id":"fan.summer.demo","name":"Demo","description":"Demo plugin",
             "version":"%s","author":"Example","icon":"puzzle-outline","category":"dev",
             "ui":{"entry":"ui/index.html"},"permissions":["files.read"]}
            """.formatted(version);
        Path archive = dir.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("ui/index.html"));
            zip.write("<html></html>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        // P0-8: a bundled official plugin now REQUIRES a matching .sha256 sidecar (the seeder is
        // fail-closed without one). Write a valid sidecar so the upgrade/version logic under test
        // is exercised end-to-end through the trusted install path.
        String hash = sha256Hex(archive);
        Files.writeString(dir.resolve(name + ".sha256"), hash + "  " + name + "\n");
    }

    private static String sha256Hex(Path file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[64 * 1024];
        try (java.io.InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) >= 0) digest.update(buf, 0, n);
        }
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return hex.toString();
    }
}
