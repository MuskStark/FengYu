package fan.summer.fengyu.plugin.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PluginPackageServiceTest {
    @TempDir Path temp;

    @Test
    void installsDisablesAndUninstallsPackage() throws Exception {
        PluginPackageService service = new PluginPackageService(temp.toString());
        PluginManifest manifest = service.install(packageFile("1.0.0"));

        assertEquals("com.example.demo", manifest.id());
        assertEquals(1, service.installed().size());
        assertTrue(service.isEnabled(manifest.id()));

        service.setEnabled(manifest.id(), false);
        assertFalse(service.isEnabled(manifest.id()));
        service.uninstall(manifest.id());
        assertTrue(service.installed().isEmpty());
    }

    @Test
    void updateReplacesVersionAndKeepsDisabledState() throws Exception {
        PluginPackageService service = new PluginPackageService(temp.toString());
        service.install(packageFile("1.0.0"));
        service.setEnabled("com.example.demo", false);
        service.install(packageFile("1.1.0"));

        assertEquals("1.1.0", service.installed().getFirst().version());
        assertFalse(service.isEnabled("com.example.demo"));
    }

    @Test
    void rejectsZipSlip() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("../escape.txt"));
            zip.write("bad".getBytes(StandardCharsets.UTF_8));
        }
        MockMultipartFile file = new MockMultipartFile("file", "bad.fyp", "application/zip", bytes.toByteArray());
        PluginPackageService service = new PluginPackageService(temp.toString());
        assertThrows(IllegalArgumentException.class, () -> service.install(file));
        assertFalse(Files.exists(temp.getParent().resolve("escape.txt")));
    }

    @Test
    void installsSharedValidFullFixture() throws Exception {
        PluginPackageService service = new PluginPackageService(temp.toString());
        PluginManifest manifest = service.install(fixturePackage("valid-full.json", "ui/index.html", "<html>full</html>"));
        assertEquals("com.example.full", manifest.id());
        // database and network.email are accepted by the shared canonical permission set.
        assertTrue(manifest.permissions().containsAll(java.util.List.of("database", "network.email")));
    }

    @Test
    void rejectsInvalidId() throws Exception {
        PluginPackageService service = new PluginPackageService(temp.toString());
        MockMultipartFile file = inlinePackage(
            "{\"schemaVersion\":1,\"id\":\"UPPER\",\"name\":\"X\",\"version\":\"1.0.0\",\"ui\":{\"entry\":\"ui/index.html\"}}",
            "ui/index.html", "<html></html>");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.install(file));
        assertTrue(ex.getMessage().toLowerCase(java.util.Locale.ROOT).contains("id"));
    }

    @Test
    void rejectsMissingUiEntry() throws Exception {
        PluginPackageService service = new PluginPackageService(temp.toString());
        // ui.entry points at ui/index.html but the archive only carries README.txt, not the UI file.
        MockMultipartFile file = inlinePackage(
            "{\"schemaVersion\":1,\"id\":\"com.example.no-ui\",\"name\":\"NoUi\",\"version\":\"1.0.0\",\"ui\":{\"entry\":\"ui/index.html\"}}",
            "README.txt", "placeholder");
        assertThrows(IllegalArgumentException.class, () -> service.install(file));
    }

    /** Locates the cross-language shared fixtures from either FengYu/ or the repository root. */
    private Path fixture(String name) {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path direct = root.resolve("plugin-spec/fixtures").resolve(name);
        return Files.exists(direct) ? direct : root.resolve("../plugin-spec/fixtures").resolve(name).normalize();
    }

    private MockMultipartFile fixturePackage(String fixtureName, String assetPath, String assetContent) throws Exception {
        String manifest = Files.readString(fixture(fixtureName), StandardCharsets.UTF_8);
        return zip("fixture.fyp", manifest, assetPath, assetContent);
    }

    private MockMultipartFile inlinePackage(String manifestJson, String assetPath, String assetContent) throws Exception {
        return zip("fixture.fyp", manifestJson, assetPath, assetContent);
    }

    private MockMultipartFile packageFile(String version) throws Exception {
        String manifest = """
            {"schemaVersion":1,"id":"com.example.demo","name":"Demo","description":"Demo plugin",
             "version":"%s","author":"Example","icon":"puzzle-outline","category":"dev",
             "ui":{"entry":"ui/index.html"},"permissions":["files.read"]}
            """.formatted(version);
        return zip("demo.fyp", manifest, "ui/index.html", "<html>demo</html>");
    }

    private MockMultipartFile zip(String filename, String manifest, String assetPath, String assetContent) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            add(zip, "manifest.json", manifest);
            add(zip, assetPath, assetContent);
        }
        return new MockMultipartFile("file", filename, "application/zip", bytes.toByteArray());
    }

    private static void add(ZipOutputStream zip, String path, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
