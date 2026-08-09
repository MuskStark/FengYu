package fan.summer.fengyu.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginRuntimeFileControllerTest {
    @TempDir Path temp;

    @Test
    void uploadDirectoryRequiresFilesReadPermission() throws Exception {
        PluginPackageService packages = new PluginPackageService(temp.resolve("plugins").toString());
        // Test plugins use a non-reserved namespace (P0-8 reserves fan.summer.* for trusted installs).
        install(packages, "com.example.email", List.of("files.read"));
        install(packages, "com.example.denied", List.of());
        PluginRuntimeFileController controller = new PluginRuntimeFileController(
            packages, new PluginFileGrantService());
        var upload = new MockMultipartFile("files", "a.txt", "text/plain", "a".getBytes());

        var response = controller.uploadDirectory(
            "com.example.email", List.of(upload), List.of("reports/a.txt"), "read");
        assertEquals("read", response.getBody().access());
        assertThrows(IllegalArgumentException.class, () -> controller.uploadDirectory(
            "com.example.denied", List.of(upload), List.of("reports/a.txt"), "read"));
    }

    @Test
    void writableWorkspaceUploadRequiresFilesWritePermission() throws Exception {
        PluginPackageService packages = new PluginPackageService(temp.resolve("plugins-write").toString());
        install(packages, "com.example.offlinepython", List.of("files.read", "files.write"));
        install(packages, "com.example.readonly", List.of("files.read"));
        install(packages, "com.example.writeonly", List.of("files.write"));
        PluginRuntimeFileController controller = new PluginRuntimeFileController(
            packages, new PluginFileGrantService());
        var upload = new MockMultipartFile("files", "requirements.txt", "text/plain", "numpy".getBytes());

        var response = controller.uploadDirectory("com.example.offlinepython", List.of(upload),
            List.of("requirements.txt"), "read-write");
        assertEquals("read-write", response.getBody().access());
        assertThrows(IllegalArgumentException.class, () -> controller.uploadDirectory(
            "com.example.readonly", List.of(upload), List.of("requirements.txt"), "read-write"));
        assertThrows(IllegalArgumentException.class, () -> controller.uploadDirectory(
            "com.example.writeonly", List.of(upload), List.of("requirements.txt"), "read-write"));
    }

    private static void install(PluginPackageService packages, String id, List<String> permissions) throws Exception {
        String manifest = """
            {"schemaVersion":1,"id":%s,"name":"Test","description":"test","version":"1.0.0",
             "author":"Test","icon":"mdi-test","category":"file",
             "ui":{"entry":"ui/index.html"},"permissions":%s}
            """.formatted(new ObjectMapper().writeValueAsString(id),
                new ObjectMapper().writeValueAsString(permissions));
        packages.install(new MockMultipartFile("file", id + ".fyp", "application/zip", archive(manifest)));
    }

    private static byte[] archive(String manifest) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("ui/index.html"));
            zip.write("test".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
