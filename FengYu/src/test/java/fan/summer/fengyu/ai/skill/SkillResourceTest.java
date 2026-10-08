package fan.summer.fengyu.ai.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    /**
     * A skill uninstalled (or otherwise broken) mid-turn surfaces as a message the model
     * can act on — the {@code skill_resource} tool call must never throw into the chat.
     */
    @Test
    void resourceToolReturnsAGracefulMessageWhenTheRegistryThrows() {
        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.readResource(anyString(), any()))
                .thenThrow(new IllegalArgumentException("Skill is not installed: dev.example.gone"));
        SkillTool tool = new SkillTool(registry);

        String result = tool.resource("dev.example.gone", "references/api.md");

        assertTrue(result.startsWith("Skill resource unavailable:"), result);
        assertTrue(result.contains("Skill is not installed"), result);
    }
}
