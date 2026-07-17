package fan.summer.fengyu.plugin.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginFileGrantServiceTest {
    @TempDir Path temp;

    @Test
    void uploadsReadableDirectoryAndPreservesSafeRelativePaths() throws Exception {
        PluginFileGrantService service = new PluginFileGrantService(temp);
        var ref = service.uploadDirectory("fan.summer.email", List.of(
            file("a", "a_Q1.pdf"), file("b", "b_Q2.pdf")),
            List.of("reports/a_Q1.pdf", "reports/b_Q2.pdf"));

        Path root = service.resolve("fan.summer.email", ref.id());
        assertEquals("directory", ref.kind());
        assertEquals("read", ref.access());
        assertTrue(Files.isRegularFile(root.resolve("reports/a_Q1.pdf")));
        assertEquals("b", Files.readString(root.resolve("reports/b_Q2.pdf")));
    }

    @Test
    void uploadsWritableWorkspaceDirectory() throws Exception {
        PluginFileGrantService service = new PluginFileGrantService(temp);
        var ref = service.uploadDirectory("fan.summer.offlinepython",
            List.of(file("numpy", "requirements.txt")), List.of("requirements.txt"), "read-write");

        assertEquals("read-write", ref.access());
        Path root = service.resolve("fan.summer.offlinepython", ref.id());
        Files.writeString(root.resolve("config.json"), "{}");
        assertEquals("{}", Files.readString(root.resolve("config.json")));
    }

    @Test
    void rejectsDirectoryTraversalAndAbsolutePaths() {
        PluginFileGrantService service = new PluginFileGrantService(temp);
        assertThrows(IllegalArgumentException.class, () -> service.uploadDirectory(
            "fan.summer.email", List.of(file("x", "x.txt")), List.of("../outside.txt")));
        assertThrows(IllegalArgumentException.class, () -> service.uploadDirectory(
            "fan.summer.email", List.of(file("x", "x.txt")), List.of(temp.resolve("outside.txt").toString())));
    }

    @Test
    void nativeReadDirectoryUsesManagedSnapshot() throws Exception {
        Path source = Files.createDirectories(temp.resolve("selected"));
        Files.writeString(source.resolve("message.txt"), "original");
        PluginFileGrantService service = new PluginFileGrantService(temp.resolve("grants"));

        var ref = service.grantNative("fan.summer.email", source.toString(), "directory", "read");
        Path granted = service.resolve("fan.summer.email", ref.id());

        assertNotEquals(source.toRealPath(), granted);
        assertEquals("original", Files.readString(granted.resolve("message.txt")));
        Files.writeString(granted.resolve("message.txt"), "changed-copy");
        assertEquals("original", Files.readString(source.resolve("message.txt")));
    }

    @Test
    void nativeReadWriteDirectoryUsesSelectedProject() throws Exception {
        Path source = Files.createDirectories(temp.resolve("workspace"));
        PluginFileGrantService service = new PluginFileGrantService(temp.resolve("grants-write"));

        var ref = service.grantNative("fan.summer.offlinepython", source.toString(),
            "directory", "read-write");
        Path granted = service.resolve("fan.summer.offlinepython", ref.id());

        assertEquals("read-write", ref.access());
        assertEquals(source.toRealPath(), granted);
    }

    private static MockMultipartFile file(String body, String name) {
        return new MockMultipartFile("files", name, "application/octet-stream", body.getBytes());
    }
}
