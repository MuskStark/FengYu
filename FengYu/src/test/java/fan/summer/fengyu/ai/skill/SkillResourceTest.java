package fan.summer.fengyu.ai.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkillResourceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void readsReferencedFileButRejectsTraversal() throws Exception {
        Path skill = temporaryDirectory.resolve("example.skill");
        Files.createDirectories(skill.resolve("references"));
        Files.writeString(skill.resolve("manifest.json"), """
                {
                  "schemaVersion": 1,
                  "id": "example.skill",
                  "name": "Example",
                  "description": "test",
                  "version": "1.0.0",
                  "official": false
                }
                """);
        Files.writeString(skill.resolve("SKILL.md"), "Read references/details.md");
        Files.writeString(skill.resolve("references/details.md"), "referenced guidance");
        SkillRegistry registry = new SkillRegistry(
                new SkillPackageService(temporaryDirectory.toString()));

        assertEquals("referenced guidance",
                registry.readResource("example.skill", "references/details.md").orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> registry.readResource("example.skill", "../outside.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> registry.readResource("example.skill", "/etc/passwd"));
    }
}
