package fan.summer.fengyu.plugin.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Startup-order regression (background seeding): the {@code ApplicationRunner} path must
 * dispatch the seed pass off the startup thread and publish per-plugin progress, while the
 * synchronous {@link OfficialPluginSeeder#seed()} keeps its direct-call semantics for tests
 * and explicit re-seeds. Also pins the precomputed-digest {@code installTrusted} contract.
 */
class OfficialPluginSeederBackgroundTest {

    @TempDir Path temp;

    @Test
    void runnerDispatchesInBackgroundAndPublishesProgress() throws Exception {
        Path packagesDir = temp.resolve("packages");
        Files.createDirectories(packagesDir);
        PluginPackageService service = new PluginPackageService(temp.resolve("installed").toString());
        writeArchive(packagesDir, "fan.summer.demo-1.0.0.fyp", "1.0.0");
        OfficialPluginSeeder seeder = new OfficialPluginSeeder(service, packagesDir.toString());

        // The runner must return before the install work completes…
        long started = System.nanoTime();
        seeder.run(null);
        long runnerReturnMs = (System.nanoTime() - started) / 1_000_000;

        // …and the background pass lands the install + progress within a generous bound.
        awaitDone(seeder, 10_000);
        assertTrue(service.find("fan.summer.demo").isPresent(), "background seed installs the plugin");
        assertTrue(runnerReturnMs < 2_000, "run() must not run the seed synchronously (took " + runnerReturnMs + " ms)");
        assertEquals(List.of(new OfficialPluginSeeder.SeedProgress("fan.summer.demo", "ready", null)),
                seeder.progress());
        seeder.shutdown();
    }

    @Test
    void emptyPackagesDirectoryStillMarksSeedingDone() throws Exception {
        Path packagesDir = temp.resolve("packages");
        Files.createDirectories(packagesDir);
        PluginPackageService service = new PluginPackageService(temp.resolve("installed").toString());
        OfficialPluginSeeder seeder = new OfficialPluginSeeder(service, packagesDir.toString());

        seeder.run(null);
        awaitDone(seeder, 5_000);
        assertTrue(seeder.seedingDone());
        assertEquals(List.of(), seeder.progress());
        seeder.shutdown();
    }

    @Test
    void installTrustedRejectsMalformedPrecomputedDigest() throws Exception {
        Path packagesDir = temp.resolve("packages");
        Files.createDirectories(packagesDir);
        PluginPackageService service = new PluginPackageService(temp.resolve("installed").toString());
        Path archive = writeArchive(packagesDir, "fan.summer.demo-1.0.0.fyp", "1.0.0");

        assertThrows(IllegalArgumentException.class, () -> service.installTrusted(archive, "not-a-digest"));
        assertThrows(IllegalArgumentException.class, () -> service.installTrusted(archive, null));
        assertTrue(service.find("fan.summer.demo").isEmpty(), "a rejected digest must not install anything");
    }

    private static void awaitDone(OfficialPluginSeeder seeder, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!seeder.seedingDone() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(seeder.seedingDone(), "background seed must finish within " + timeoutMs + " ms");
    }

    private Path writeArchive(Path dir, String name, String version) throws Exception {
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
        Files.writeString(dir.resolve(name + ".sha256"), OfficialPluginSeeder.sha256Hex(archive) + "  " + name + "\n");
        return archive;
    }
}
