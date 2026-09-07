package fan.summer.fengyu.ai.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    /**
     * Discovery is snapshotted and cached for a short TTL: repeated reads within the TTL
     * do not rescan (the per-message appender path stays cheap), while an explicit
     * invalidation — what install/uninstall/enable/disable call — publishes changes
     * immediately.
     */
    @Test
    void discoveryIsCachedUntilInvalidatedOrTheTtlExpires() throws Exception {
        Path dir = Files.createDirectories(temp.resolve("dev.example.cached"));
        Files.writeString(dir.resolve("SKILL.md"), "cached guidance");

        SkillPackageService packages = mock(SkillPackageService.class);
        AtomicInteger scans = new AtomicInteger();
        when(packages.installed()).thenAnswer(invocation -> {
            scans.incrementAndGet();
            return List.of(new SkillManifest(1, "dev.example.cached", "Cached", "d", "1.0.0",
                    "x", null, null, false));
        });
        when(packages.directory("dev.example.cached")).thenReturn(dir);
        SkillRegistry registry = new SkillRegistry(packages);

        registry.all();                       // first read scans
        registry.all();
        registry.enabled();
        registry.find("dev.example.cached");
        assertEquals(1, scans.get(), "reads inside the TTL are served from the snapshot");

        registry.invalidateCache();           // the lifecycle hooks' immediate publish
        registry.all();
        assertEquals(2, scans.get(), "invalidation forces the next read to rescan");

        registry.setEnabled("dev.example.cached", false); // enable/disable also invalidates
        registry.all();
        assertEquals(3, scans.get(), "toggling enabled drops the snapshot");
        verify(packages).setEnabled("dev.example.cached", false);
    }
}
