package fan.summer.fengyu.plugin.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private void writeArchive(Path dir, String name, String version) throws Exception {
        String manifest = """
            {"schemaVersion":1,"id":"fan.summer.demo","name":"Demo","description":"Demo plugin",
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
}
