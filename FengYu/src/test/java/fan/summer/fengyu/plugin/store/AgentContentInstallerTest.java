package fan.summer.fengyu.plugin.store;

import fan.summer.fengyu.FengYuApplication;
import fan.summer.fengyu.database.SecurityConstants;
import fan.summer.fengyu.database.repository.PluginInstallRecordRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = FengYuApplication.class)
class AgentContentInstallerTest {

    @TempDir Path temp;
    @Autowired private PluginInstallRecordRepository records;

    @Test
    void clonesPinnedShaExtractsSkillsAndMcp() throws Exception {
        // 1. Build a tiny source repo on disk.
        Path repo = temp.resolve("src-repo");
        Files.createDirectories(repo);
        try (Git g = Git.init().setDirectory(repo.toFile()).call()) {
            Files.createDirectories(repo.resolve("skills"));
            Files.writeString(repo.resolve("skills/SKILL.md"), "---\nname: demo\n---\n# demo");
            Files.createDirectories(repo.resolve(".claude-plugin"));
            Files.writeString(repo.resolve(".claude-plugin/plugin.json"),
                "{\"name\":\"demo\",\"version\":\"1.0.0\",\"description\":\"d\","
                + "\"skills\":[\"skills/SKILL.md\"],"
                + "\"mcpServers\":{\"demo\":{\"type\":\"http\",\"url\":\"https://x/mcp\"}}}");
            g.add().addFilepattern(".").call();
            ObjectId head = g.commit().setMessage("init").setSign(false).call().getId();
            String sha = head.getName();

            // 2. Point the installer at this repo via a file:// remote.
            Path runtimeRoot = temp.resolve("runtime");
            UnifiedCatalogEntry.SourceRef ref = new UnifiedCatalogEntry.GitUrlSource("file://" + repo, sha);
            UnifiedCatalogEntry entry = new UnifiedCatalogEntry(
                "test:CLAUDE:demo", "test", StoreSourceType.CLAUDE, "demo", "demo", "d",
                null, null, List.of(), null, sha, ref,
                List.of(), List.of(), null, false, null, false, false);

            AgentContentInstaller installer = new AgentContentInstaller(records, runtimeRoot, 60);
            installer.install(entry);

            // 3. skill copied under runtimeRoot/skills/<uid>/
            Path skillDir = runtimeRoot.resolve("skills").resolve("test:CLAUDE:demo");
            assertTrue(Files.exists(skillDir.resolve("skills/SKILL.md")), "skill file should be copied");

            // 4. mcp config persisted under runtimeRoot/mcp-servers/<uid>.json
            Path mcpFile = runtimeRoot.resolve("mcp-servers").resolve("test:CLAUDE:demo.json");
            assertTrue(Files.exists(mcpFile), "mcp config should be written");

            // 5. install record persisted (same transaction — visible before rollback)
            var rec = records.findByUidAndUserId("test:CLAUDE:demo", SecurityConstants.LOCAL_VIRTUAL_USER_ID);
            assertTrue(rec.isPresent());
            assertTrue(rec.get().isHasMcpServers());
            assertEquals(sha, rec.get().getPinnedSha());
        }
    }

    @Test
    void rejectsShaMismatch() throws Exception {
        Path repo = temp.resolve("src-repo");
        Files.createDirectories(repo);
        try (Git g = Git.init().setDirectory(repo.toFile()).call()) {
            Files.createDirectories(repo.resolve(".claude-plugin"));
            Files.writeString(repo.resolve(".claude-plugin/plugin.json"),
                "{\"name\":\"demo\",\"version\":\"1.0.0\",\"description\":\"d\"}");
            g.add().addFilepattern(".").call();
            g.commit().setMessage("init").setSign(false).call();

            String wrongSha = "deadbeef".repeat(5);
            UnifiedCatalogEntry.SourceRef ref = new UnifiedCatalogEntry.GitUrlSource("file://" + repo, wrongSha);
            UnifiedCatalogEntry entry = new UnifiedCatalogEntry(
                "test:CLAUDE:demo", "test", StoreSourceType.CLAUDE, "demo", "demo", "d",
                null, null, List.of(), null, wrongSha, ref,
                List.of(), List.of(), null, false, null, false, false);

            AgentContentInstaller installer = new AgentContentInstaller(records, temp.resolve("runtime"), 60);
            assertThrows(IntegrityException.class, () -> installer.install(entry));
        }
    }
}
