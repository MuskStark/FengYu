package fan.summer.fengyu.plugin.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PluginFileGrantServiceTest {
    @TempDir Path temp;

    /**
     * P2-12: a LIVE write-capable native grant into the well-known credential directories
     * (~/.ssh, ~/.aws, …) would hand a compromised renderer/worker a direct
     * credential-tampering primitive — refused by default. Probed against a pinned fake
     * user.home so the test never depends on (or touches) the real home directory.
     */
    @Test
    void liveWriteGrantsIntoSensitiveHomeDirectoriesAreRefused() throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", temp.toString());
        try {
            PluginFileGrantService service = new PluginFileGrantService(temp.resolve("runtime-files"));
            Path sshDir = Files.createDirectories(temp.resolve(".ssh"));
            Path awsDir = Files.createDirectories(temp.resolve(".aws"));

            IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                    () -> service.grantLive("fan.summer.evil", sshDir, "directory", "read-write"));
            assertTrue(denied.getMessage().contains("sensitive directory"),
                    "the refusal must name the sensitive location; got: " + denied.getMessage());
            assertThrows(IllegalArgumentException.class,
                    () -> service.grantLive("fan.summer.evil", awsDir, "directory", "read-write"));

            // A subdirectory inside a sensitive tree is refused too (startsWith semantics).
            Path awsConfig = Files.createDirectories(awsDir.resolve("credentials"));
            assertThrows(IllegalArgumentException.class,
                    () -> service.grantLive("fan.summer.evil", awsConfig, "directory", "write"));

            // A read grant of the same location is NOT refused here (it snapshots bounded
            // content) — only live writes into credentials are denied.
            // (snapshot() requires a regular file/directory under quota; the point is that
            // requireNotSensitiveForWrite never runs for read access.)
            Path harmless = Files.createDirectories(temp.resolve("plain-project"));
            assertDoesNotThrow(() -> {
                try {
                    service.grantLive("fan.summer.email", harmless, "directory", "write");
                } catch (IllegalArgumentException unrelated) {
                    // quota/registration verdicts are fine; the sensitive-dir refusal must not fire
                    assertTrue(!unrelated.getMessage().contains("sensitive directory"));
                }
            });
        } finally {
            System.setProperty("user.home", realHome);
        }
    }

    /**
     * P3: grants live in memory only, so every directory under the runtime-files root at startup
     * is an orphan from a crashed run — the constructor sweeps them instead of accumulating.
     */
    @Test
    void startupSweepsLeftoverRuntimeFilesFromACrashedRun() throws Exception {
        Path leftoverUpload = Files.createDirectories(temp.resolve("upload-abc"));
        Files.writeString(leftoverUpload.resolve("file.txt"), "orphaned upload");
        Files.createDirectories(temp.resolve("native-snapshot"));
        Files.createDirectories(temp.resolve("_shared"));

        new PluginFileGrantService(temp);

        try (var entries = Files.list(temp)) {
            assertTrue(entries.noneMatch(Files::isDirectory),
                "no leftover directories may survive the startup sweep");
        }
    }

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

    /** Every failed upload path must leave the runtime-files root free of orphan directories. */
    @Test
    void failedUploadsLeaveNoOrphanDirectories() throws Exception {
        Path root = temp.resolve("grants-clean");
        PluginFileGrantService service = new PluginFileGrantService(root);
        String pluginId = "fan.summer.email";

        // 1) a later empty entry rejects BEFORE any directory is created (nothing partial to strand)
        var empty = new MockMultipartFile("files", "b.txt", "application/octet-stream", new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> service.uploadDirectory(
            pluginId, List.of(file("a", "a.txt"), empty), List.of("a.txt", "b.txt")));
        assertNoOrphanDirs(root, pluginId);

        // 2) a mid-copy stream failure cleans the partially written directory
        MultipartFile exploding = mock(MultipartFile.class);
        when(exploding.isEmpty()).thenReturn(false);
        when(exploding.getSize()).thenReturn(5L);
        when(exploding.getInputStream()).thenThrow(new IOException("disk full"));
        assertThrows(IOException.class, () -> service.uploadDirectory(
            pluginId, List.of(exploding), List.of("x.txt")));
        assertNoOrphanDirs(root, pluginId);

        // 3) escaping and duplicate-normalizing entries are pure-data rejections
        assertThrows(IllegalArgumentException.class, () -> service.uploadDirectory(
            pluginId, List.of(file("x", "x.txt")), List.of("ok/../x.txt", "../escape.txt")));
        assertThrows(IllegalArgumentException.class, () -> service.uploadDirectory(
            pluginId, List.of(file("x", "x.txt"), file("y", "y.txt")), List.of("b.txt", "./b.txt")));
        assertNoOrphanDirs(root, pluginId);

        // 4) a single-file upload whose stream dies mid-copy is reclaimed too
        MultipartFile explodingSingle = mock(MultipartFile.class);
        when(explodingSingle.isEmpty()).thenReturn(false);
        when(explodingSingle.getSize()).thenReturn(5L);
        when(explodingSingle.getOriginalFilename()).thenReturn("z.txt");
        when(explodingSingle.getInputStream()).thenThrow(new IOException("disk full"));
        assertThrows(IOException.class, () -> service.upload(pluginId, explodingSingle));
        assertNoOrphanDirs(root, pluginId);
    }

    /** Hitting the active-grant cap inside register() must not strand the freshly copied tree. */
    @Test
    void grantCapFailureDuringUploadCleansTheDirectory() throws Exception {
        Path scratch = Files.createDirectories(temp.resolve("scratch"));
        Path root = temp.resolve("grants-cap");
        PluginFileGrantService service = new PluginFileGrantService(root);
        String pluginId = "fan.summer.bulk";
        for (int i = 0; i < 1_000; i++) service.grantLive(pluginId, scratch, "directory", "read");

        assertThrows(IllegalStateException.class, () -> service.upload(pluginId, file("x", "x.txt")));
        assertNoOrphanDirs(root, pluginId);
        assertThrows(IllegalStateException.class, () -> service.outputDirectory(pluginId));
        assertNoOrphanDirs(root, pluginId);
    }

    /** A successful upload must NOT be swept by its own failure guards (control for the above). */
    @Test
    void successfulUploadSurvivesUntilRevoked() throws Exception {
        Path root = temp.resolve("grants-ok");
        PluginFileGrantService service = new PluginFileGrantService(root);
        var ref = service.upload("fan.summer.email", file("payload", "p.txt"));
        Path dir = service.resolve("fan.summer.email", ref.id()).getParent();
        assertTrue(Files.isDirectory(dir), "control: the uploaded file exists until revoke");
        service.revoke("fan.summer.email", ref.id());
        assertTrue(Files.notExists(dir), "revoke still reclaims the tree");
    }

    private static void assertNoOrphanDirs(Path root, String pluginId) throws Exception {
        Path pluginDir = root.resolve(pluginId);
        if (!Files.exists(pluginDir)) return;
        try (var stream = Files.list(pluginDir)) {
            assertEquals(0, stream.count(),
                    "no orphan upload directories may remain for " + pluginId);
        }
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

    /**
     * Two live grants for the SAME shared scratch path revoked concurrently: each thread
     * may see the other's grant already gone, so both can believe they hold the last grant
     * and race deleteTree on the same tree. Entries vanishing mid-walk surface as
     * UncheckedIOException — revoke must swallow that (never abort the caller's remaining
     * revocations) and the tree must still end up reclaimed.
     */
    @Test
    void concurrentRevokesOfTheLastSharedGrantsReclaimTheTreeWithoutThrowing() throws Exception {
        PluginFileGrantService service = new PluginFileGrantService(temp.resolve("grants-shared"));
        Path shared = service.createSharedDirectory();
        Files.writeString(shared.resolve("handoff.txt"), "data");
        var excelRef = service.grantLive("fan.summer.excel", shared, "directory", "read-write");
        var emailRef = service.grantLive("fan.summer.email", shared, "directory", "read");

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> service.revoke("fan.summer.excel", excelRef.id()));
            var b = executor.submit(() -> service.revoke("fan.summer.email", emailRef.id()));
            a.get(10, java.util.concurrent.TimeUnit.SECONDS); // Future.get rethrows unexpected failures
            b.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertTrue(Files.notExists(shared), "the shared scratch tree must be reclaimed");
    }

    /** C1 regression: degenerate client-controlled filenames must fall back to a neutral name,
     *  not NPE ("" / ".." yield a null {@code getFileName()}) and not resolve onto the grant dir. */
    @Test
    void fallsBackToANeutralNameForDegenerateUploadFilenames() throws Exception {
        PluginFileGrantService service = new PluginFileGrantService(temp);
        for (String degenerate : new String[] { null, "", "..", ".", "a/.." }) {
            var ref = service.upload("fan.summer.email", file("content", degenerate));
            // A file-kind resolve() returns the stored file itself.
            Path stored = service.resolve("fan.summer.email", ref.id());
            assertTrue(Files.isRegularFile(stored),
                    "filename '" + degenerate + "' must fall back to 'file'");
            assertEquals("file", stored.getFileName().toString());
            assertEquals("content", Files.readString(stored));
        }
    }

    private static MockMultipartFile file(String body, String name) {
        return new MockMultipartFile("files", name, "application/octet-stream", body.getBytes());
    }
}
