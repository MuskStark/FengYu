package fan.summer.fengyu.plugin.workspace;

import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

class PluginWorkspaceServiceTest {

    PluginWorkspaceService svc;

    @BeforeEach
    void setUp() { svc = new PluginWorkspaceService(); }

    @Test
    void storeAndZip() throws Exception {
        String id = "fan.summer.excel";
        String sess = svc.newSession();
        Path stored = svc.store(id, sess, "a.txt",
            new ByteArrayInputStream("hello".getBytes()));
        assertTrue(Files.exists(stored));
        assertTrue(stored.startsWith(svc.inDir(id, sess)));

        Files.writeString(svc.outDir(id, sess).resolve("r.xlsx"), "data");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        svc.zipDir(svc.outDir(id, sess), bos);
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
            ZipEntry e = zis.getNextEntry();
            assertNotNull(e);
            assertEquals("r.xlsx", e.getName());
        }
        Path sessionDir = svc.inDir(id, sess).getParent();
        svc.remove(id, sess);
        assertFalse(Files.exists(sessionDir));
    }

    @Test
    void rejectsTraversalFilename() {
        String sess = svc.newSession();
        assertThrows(IllegalArgumentException.class,
            () -> svc.store("fan.summer.excel", sess, "../evil.txt",
                new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void rejectsBadSession() {
        assertThrows(IllegalArgumentException.class,
            () -> svc.inDir("fan.summer.excel", "../../etc"));
    }
}
