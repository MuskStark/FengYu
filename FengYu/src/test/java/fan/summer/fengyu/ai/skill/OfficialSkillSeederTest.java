package fan.summer.fengyu.ai.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The trusted seeder must be opt-in: with no explicit
 * {@code fengyu.skills.official-directory} configured it does nothing — the legacy
 * implicit default pointed at a developer working-directory convention
 * ({@code ${user.dir}/OfficialSkills/target/packages}) that left this repository, and an
 * implicit trusted-install path must not survive that.
 */
class OfficialSkillSeederTest {

    @TempDir
    Path temp;

    @Test
    void blankConfigurationDisablesSeedingEntirely() {
        SkillPackageService packages = mock(SkillPackageService.class);

        new OfficialSkillSeeder(packages, "  ").seed();

        verifyNoInteractions(packages);
    }

    @Test
    void anExplicitDirectoryStillSeedsTrustedPackages() throws Exception {
        SkillPackageService packages =
                new SkillPackageService(temp.resolve("skills").toString());
        Path source = Files.createDirectories(temp.resolve("packages"));
        try (ZipOutputStream out = new ZipOutputStream(
                Files.newOutputStream(source.resolve("fan.summer.bundled-skill-1.0.0.fys")))) {
            out.putNextEntry(new ZipEntry("manifest.json"));
            out.write(("""
                    {"schemaVersion":1,"id":"fan.summer.bundled-skill","name":"Bundled",
                     "description":"d","version":"1.0.0","author":"FengYu","official":true}
                    """).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("SKILL.md"));
            out.write("# Guidance".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        OfficialSkillSeeder seeder = new OfficialSkillSeeder(packages, source.toString());

        seeder.seed();

        assertTrue(packages.find("fan.summer.bundled-skill").isPresent(),
                "an explicitly configured directory keeps seeding through the trusted path");
    }
}
