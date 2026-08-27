package fan.summer.fengyu.ai.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillPackageServiceTest {
    @TempDir Path temp;

    /**
     * C5 regression: the expanded-bytes cap cannot see zero-byte entries — a flood of empty
     * entries must be rejected by the entry-count cap (the plugin installer's twin guard)
     * instead of exhausting inodes in the skills root.
     */
    @Test
    void rejectsArchiveFloodedWithEmptyEntries() throws Exception {
        SkillPackageService service = new SkillPackageService(temp.resolve("skills").toString());
        Path archive = temp.resolve("flood.fys");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (int i = 0; i <= 10_000; i++) {
                out.putNextEntry(new ZipEntry("e" + i + "/"));
                out.closeEntry();
            }
        }

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.install(archive));

        assertTrue(error.getMessage().contains("entries"));
    }
}
