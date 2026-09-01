package fan.summer.fengyu.ai.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillPackageServiceTest {
    @TempDir Path temp;

    private SkillPackageService service() {
        return new SkillPackageService(temp.resolve("skills").toString());
    }

    /** Builds a minimal valid .fys with the given manifest fields. */
    private Path fys(String id, String version, boolean official) throws Exception {
        Path archive = temp.resolve(id + "-" + version + ".fys");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            out.putNextEntry(new ZipEntry("manifest.json"));
            String manifest = """
                    {"schemaVersion":1,"id":"%s","name":"%s","description":"d",
                     "version":"%s","author":"someone","official":%s}
                    """.formatted(id, id, version, official);
            out.write(manifest.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("SKILL.md"));
            out.write("# Guidance\nUse it well.".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return archive;
    }

    /**
     * C5 regression: the expanded-bytes cap cannot see zero-byte entries — a flood of empty
     * entries must be rejected by the entry-count cap (the plugin installer's twin guard)
     * instead of exhausting inodes in the skills root.
     */
    @Test
    void rejectsArchiveFloodedWithEmptyEntries() throws Exception {
        SkillPackageService service = service();
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

    @Test
    void uploadsCannotClaimTheOfficialIdentity() throws Exception {
        SkillPackageService service = service();
        Path archive = fys("fan.summer.sneaky", "1.0.0", true);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.install(archive));

        assertTrue(error.getMessage().contains("Official skills are only installed"),
                error.getMessage());
        assertTrue(Files.notExists(temp.resolve("skills").resolve("fan.summer.sneaky")),
                "nothing is published");
    }

    @Test
    void trustedPathMayInstallOfficialPackages() throws Exception {
        SkillPackageService service = service();
        Path archive = fys("fan.summer.bundled-skill", "1.0.0", true);

        SkillManifest installed = assertDoesNotThrow(() -> service.installTrusted(archive));

        assertTrue(installed.official());
        assertTrue(Files.isRegularFile(temp.resolve("skills")
                .resolve("fan.summer.bundled-skill").resolve("SKILL.md")));
    }

    @Test
    void builtinSkillIdsCannotBeOverriddenEvenByTheTrustedPath() throws Exception {
        SkillPackageService service = service();
        // fengyu-plugin-dev ships as a classpath builtin skill in this JAR.
        Path archive = fys("fengyu-plugin-dev", "99.0.0", false);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.installTrusted(archive));

        assertTrue(error.getMessage().contains("builtin skill"), error.getMessage());
    }
}
