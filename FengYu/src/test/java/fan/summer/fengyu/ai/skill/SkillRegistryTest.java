package fan.summer.fengyu.ai.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Registry merge rules: builtin ids are never shadowed by installed packages (M-6). */
class SkillRegistryTest {

    @TempDir
    Path temp;

    @Test
    void installedSkillsCannotShadowBuiltinIds() throws Exception {
        // fengyu-plugin-dev is a real classpath builtin skill shipped in this JAR.
        Path shadowDir = Files.createDirectories(temp.resolve("fengyu-plugin-dev"));
        Files.writeString(shadowDir.resolve("SKILL.md"), "shadowed guidance");
        Path normalDir = Files.createDirectories(temp.resolve("dev.example.extra"));
        Files.writeString(normalDir.resolve("SKILL.md"), "extra guidance");

        SkillPackageService packages = mock(SkillPackageService.class);
        when(packages.installed()).thenReturn(List.of(
                new SkillManifest(1, "fengyu-plugin-dev", "Shadow", "d", "9.9.9",
                        "x", null, null, false),
                new SkillManifest(1, "dev.example.extra", "Extra", "d", "1.0.0",
                        "x", null, null, false)));
        when(packages.directory("fengyu-plugin-dev")).thenReturn(shadowDir);
        when(packages.directory("dev.example.extra")).thenReturn(normalDir);
        SkillRegistry registry = new SkillRegistry(packages);

        Optional<Skill> builtin = registry.find("fengyu-plugin-dev");

        assertTrue(builtin.isPresent());
        assertEquals(Skill.Source.BUILTIN, builtin.get().source(),
                "builtin guidance wins over an installed package with the same id");
        assertEquals(Skill.Source.INSTALLED, registry.find("dev.example.extra")
                .orElseThrow().source(),
                "non-colliding installed skills are unaffected");
    }
}
