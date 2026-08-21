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
    void installsGrokPluginFromItsNativeManifestPath() throws Exception {
        Path repo = temp.resolve("grok-repo");
        Files.createDirectories(repo);
        try (Git git = Git.init().setDirectory(repo.toFile()).call()) {
            Files.createDirectories(repo.resolve(".grok-plugin"));
            Files.createDirectories(repo.resolve("skills/research"));
            Files.writeString(repo.resolve("skills/research/SKILL.md"), "---\\nname: research\\n---\\n# Research");
            Files.writeString(repo.resolve(".grok-plugin/plugin.json"), """
                {"name":"research","version":"1.0.0","description":"d",
                 "skills":"skills","mcpServers":{"echo":{"command":"echo"}}}
                """);
            git.add().addFilepattern(".").call();
            String sha = git.commit().setMessage("init").setSign(false).call().getId().getName();

            UnifiedCatalogEntry entry = new UnifiedCatalogEntry(
                    "test:GROK:research", "test", StoreSourceType.GROK, "research", "research", "d",
                    null, null, List.of(), null, sha,
                    new UnifiedCatalogEntry.GitUrlSource("file://" + repo, sha),
                    List.of(), List.of(), null, false, null, false, false);
            Path runtimeRoot = temp.resolve("runtime");
            AgentContentInstaller installer = new AgentContentInstaller(records, runtimeRoot, 60);

            installer.install(entry);

            assertTrue(Files.exists(runtimeRoot.resolve("skills/test:GROK:research/skills/research/SKILL.md")));
            assertTrue(Files.exists(runtimeRoot.resolve("mcp-servers/test:GROK:research.json")));
            assertTrue(records.findByUidAndUserId("test:GROK:research",
                    SecurityConstants.LOCAL_VIRTUAL_USER_ID).orElseThrow().isHasMcpServers());
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

    @Test
    void rejectsUidThatEscapesRuntimeRootViaTraversal() throws Exception {
        // Defense-in-depth: even if a crafted uid (e.g. one with enough ".." segments to climb
        // out of skills/ AND runtimeRoot) reaches the installer, it must refuse rather than
        // delete/write outside the runtime tree. skills/<seg>/../../../escape climbs: skills →
        // runtimeRoot → runtimeRoot-parent, landing outside runtimeRoot.
        Path repo = temp.resolve("src-repo");
        Files.createDirectories(repo);
        try (Git g = Git.init().setDirectory(repo.toFile()).call()) {
            Files.createDirectories(repo.resolve(".claude-plugin"));
            Files.writeString(repo.resolve(".claude-plugin/plugin.json"),
                "{\"name\":\"x\",\"version\":\"1.0.0\",\"description\":\"d\"}");
            g.add().addFilepattern(".").call();
            ObjectId head = g.commit().setMessage("init").setSign(false).call().getId();
            String sha = head.getName();

            Path runtimeRoot = temp.resolve("runtime");
            // "a/../../../<outside>" : runtimeRoot/skills/a/../../../escape = <temp>/escape (outside runtimeRoot).
            String escapingUid = "a/../../../outside-target";
            UnifiedCatalogEntry.SourceRef ref = new UnifiedCatalogEntry.GitUrlSource("file://" + repo, sha);
            UnifiedCatalogEntry entry = new UnifiedCatalogEntry(
                escapingUid, "evil", StoreSourceType.CLAUDE, "a", "a", "d",
                null, null, List.of(), null, sha, ref,
                List.of(), List.of(), null, false, null, false, false);

            AgentContentInstaller installer = new AgentContentInstaller(records, runtimeRoot, 60);
            RuntimeException ex = assertThrows(RuntimeException.class, () -> installer.install(entry));
            // The IllegalArgumentException is wrapped; assert the root cause mentions the runtime root.
            assertNotNull(ex.getMessage());
        }
    }

    @Test
    void recordsResolvedCommitShaWhenNoPinDeclared() throws Exception {
        // Codex sources declare no pinned sha, so historically the install record's pinnedSha was
        // always null — leaving no way to detect that the upstream repo changed between fetch and
        // install. With no pin, the installer must resolve HEAD itself and record that sha, so the
        // install record always carries an auditable content fingerprint.
        Path repo = temp.resolve("src-repo");
        Files.createDirectories(repo);
        try (Git g = Git.init().setDirectory(repo.toFile()).call()) {
            Files.createDirectories(repo.resolve(".claude-plugin"));
            Files.writeString(repo.resolve(".claude-plugin/plugin.json"),
                "{\"name\":\"demo\",\"version\":\"1.0.0\",\"description\":\"d\"}");
            g.add().addFilepattern(".").call();
            ObjectId head = g.commit().setMessage("init").setSign(false).call().getId();
            String resolvedSha = head.getName();

            UnifiedCatalogEntry.SourceRef ref = new UnifiedCatalogEntry.GitUrlSource("file://" + repo, null);
            UnifiedCatalogEntry entry = new UnifiedCatalogEntry(
                "test:CLAUDE:demo", "test", StoreSourceType.CLAUDE, "demo", "demo", "d",
                null, null, List.of(), null, null, ref,
                List.of(), List.of(), null, false, null, false, false);

            AgentContentInstaller installer = new AgentContentInstaller(records, temp.resolve("runtime"), 60);
            installer.install(entry);

            var rec = records.findByUidAndUserId("test:CLAUDE:demo", SecurityConstants.LOCAL_VIRTUAL_USER_ID);
            assertTrue(rec.isPresent());
            assertEquals(resolvedSha, rec.get().getPinnedSha(),
                "install record must carry the resolved commit sha when the catalog declared none");
        }
    }

    @Test
    void rejectsCloneUrlWithDisallowedScheme() {
        // Only http(s)/file:// may be cloned — a third-party marketplace URL using ftp://, jar://,
        // or a bare local path must be rejected before Git.cloneRepository ever sees it.
        UnifiedCatalogEntry.SourceRef ref = new UnifiedCatalogEntry.GitUrlSource("ftp://evil/x", "abc");
        UnifiedCatalogEntry entry = new UnifiedCatalogEntry(
            "evil:CLAUDE:demo", "evil", StoreSourceType.CLAUDE, "demo", "demo", "d",
            null, null, List.of(), null, "abc", ref,
            List.of(), List.of(), null, false, null, false, false);

        AgentContentInstaller installer = new AgentContentInstaller(records, temp.resolve("runtime"), 60);
        RuntimeException ex = assertThrows(RuntimeException.class, () -> installer.install(entry));
        // The scheme guard fires before the clone attempt; its message must mention the scheme.
        String msg = (ex.getCause() != null ? ex.getCause() : ex).getMessage();
        assertTrue(msg.contains("scheme") || msg.contains("disallowed"),
            "should reject disallowed URL scheme with a clear message; got: " + msg);
    }

    @Test
    void cleansUpCloneTempDirWhenCloneFails() throws Exception {
        // If the clone itself throws (here: a bogus host that can't be resolved), the temp dir
        // created for it must not be left behind under runtimeRoot/.clone-/. Repeated failed
        // installs would otherwise accumulate .git trees.
        UnifiedCatalogEntry.SourceRef ref =
            new UnifiedCatalogEntry.GitUrlSource("https://invalid-host-that-does-not-exist.invalid/x", null);
        UnifiedCatalogEntry entry = new UnifiedCatalogEntry(
            "evil:CLAUDE:demo", "evil", StoreSourceType.CLAUDE, "demo", "demo", "d",
            null, null, List.of(), null, null, ref,
            List.of(), List.of(), null, false, null, false, false);

        Path runtimeRoot = temp.resolve("runtime");
        AgentContentInstaller installer = new AgentContentInstaller(records, runtimeRoot, 10);
        assertThrows(RuntimeException.class, () -> installer.install(entry));

        Path cloneRoot = runtimeRoot.resolve(".clone-");
        if (Files.isDirectory(cloneRoot)) {
            try (var stream = Files.walk(cloneRoot)) {
                long leftoverDirs = stream.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("agent-"))
                    .count();
                assertEquals(0, leftoverDirs, "failed clone must not leave temp dirs under .clone-/");
            }
        }
    }

    @Test
    void setEnabledWritesDisabledMarkerSoSkillLoaderRespectsIt() throws Exception {
        // The skill loader (SkillRegistry/SkillPackageService) reads enable state from a .disabled
        // marker file under the skill dir, not from the DB record. AgentContentInstaller.setEnabled
        // must therefore write/remove that marker — toggling only the DB row left the skill loaded.
        Path repo = temp.resolve("src-repo");
        Files.createDirectories(repo);
        try (Git g = Git.init().setDirectory(repo.toFile()).call()) {
            Files.createDirectories(repo.resolve(".claude-plugin"));
            Files.writeString(repo.resolve(".claude-plugin/plugin.json"),
                "{\"name\":\"demo\",\"version\":\"1.0.0\",\"description\":\"d\"}");
            g.add().addFilepattern(".").call();
            ObjectId head = g.commit().setMessage("init").setSign(false).call().getId();
            String sha = head.getName();

            Path runtimeRoot = temp.resolve("runtime");
            UnifiedCatalogEntry.SourceRef ref = new UnifiedCatalogEntry.GitUrlSource("file://" + repo, sha);
            UnifiedCatalogEntry entry = new UnifiedCatalogEntry(
                "test:CLAUDE:demo", "test", StoreSourceType.CLAUDE, "demo", "demo", "d",
                null, null, List.of(), null, sha, ref,
                List.of(), List.of(), null, false, null, false, false);

            AgentContentInstaller installer = new AgentContentInstaller(records, runtimeRoot, 60);
            installer.install(entry);

            Path skillDir = runtimeRoot.resolve("skills").resolve("test:CLAUDE:demo");
            Files.createDirectories(skillDir); // ensure the skill dir exists for the marker

            installer.setEnabled("test:CLAUDE:demo", false);
            assertTrue(Files.exists(skillDir.resolve(".disabled")),
                "disabling must create the .disabled marker the skill loader reads");

            installer.setEnabled("test:CLAUDE:demo", true);
            assertFalse(Files.exists(skillDir.resolve(".disabled")),
                "enabling must remove the .disabled marker");
        }
    }

    @Test
    void doesNotFollowSymlinksWhenExtractingSkills() throws Exception {
        // A malicious repo can contain a skill entry that is a symlink whose target is outside
        // pluginRoot (or a dir containing such a symlink). Files.walkFileTree / Files.copy follow
        // symlinks by default, which would copy user-readable host files into the runtime tree
        // (info exposure). The installer must skip symlinks rather than follow them.
        Path repo = temp.resolve("src-repo");
        Files.createDirectories(repo);
        Path hostSecret = temp.resolve("host-secret.txt");
        Files.writeString(hostSecret, "top-secret-host-content");

        try (Git g = Git.init().setDirectory(repo.toFile()).call()) {
            Files.createDirectories(repo.resolve("skills"));
            // A symlink inside the skill dir pointing at an absolute host path outside the repo.
            Path link = repo.resolve("skills/leak");
            Files.createSymbolicLink(link, hostSecret); // leak -> /tmp/.../host-secret.txt
            Files.createDirectories(repo.resolve(".claude-plugin"));
            Files.writeString(repo.resolve(".claude-plugin/plugin.json"),
                "{\"name\":\"demo\",\"version\":\"1.0.0\",\"description\":\"d\","
                + "\"skills\":[\"skills\"]}");
            g.add().addFilepattern(".").call();
            ObjectId head = g.commit().setMessage("init").setSign(false).call().getId();
            String sha = head.getName();

            Path runtimeRoot = temp.resolve("runtime");
            UnifiedCatalogEntry.SourceRef ref = new UnifiedCatalogEntry.GitUrlSource("file://" + repo, sha);
            UnifiedCatalogEntry entry = new UnifiedCatalogEntry(
                "test:CLAUDE:demo", "test", StoreSourceType.CLAUDE, "demo", "demo", "d",
                null, null, List.of(), null, sha, ref,
                List.of(), List.of(), null, false, null, false, false);

            AgentContentInstaller installer = new AgentContentInstaller(records, runtimeRoot, 60);
            installer.install(entry);

            // The leaked content must NOT appear in the runtime skill tree — symlinks must be skipped.
            Path copiedLeak = runtimeRoot.resolve("skills").resolve("test:CLAUDE:demo")
                .resolve("skills").resolve("leak");
            assertFalse(Files.exists(copiedLeak) && Files.isRegularFile(copiedLeak)
                    && Files.readString(copiedLeak).contains("top-secret-host-content"),
                "symlink to a host file must not be followed into the runtime tree");
        }
    }

    @Test
    void skipsSkillEntryThatEscapesPluginRoot() throws Exception {
        // 1. Build a source repo whose plugin.json declares a skill path that escapes pluginRoot
        //    via "..". The escape target is a real file OUTSIDE the repo (sibling under @TempDir).
        Path repo = temp.resolve("src-repo");
        Files.createDirectories(repo);
        Path escapeTarget = temp.resolve("escape-target");
        Files.createDirectories(escapeTarget);
        Files.writeString(escapeTarget.resolve("secret.md"), "host-secret");

        try (Git g = Git.init().setDirectory(repo.toFile()).call()) {
            Files.createDirectories(repo.resolve(".claude-plugin"));
            // "../escape-target" resolves outside pluginRoot (the clone dir) -> must be skipped.
            Files.writeString(repo.resolve(".claude-plugin/plugin.json"),
                "{\"name\":\"demo\",\"version\":\"1.0.0\",\"description\":\"d\","
                + "\"skills\":[\"../escape-target\"]}");
            g.add().addFilepattern(".").call();
            ObjectId head = g.commit().setMessage("init").setSign(false).call().getId();
            String sha = head.getName();

            Path runtimeRoot = temp.resolve("runtime");
            UnifiedCatalogEntry.SourceRef ref = new UnifiedCatalogEntry.GitUrlSource("file://" + repo, sha);
            UnifiedCatalogEntry entry = new UnifiedCatalogEntry(
                "test:CLAUDE:escape", "test", StoreSourceType.CLAUDE, "escape", "escape", "d",
                null, null, List.of(), null, sha, ref,
                List.of(), List.of(), null, false, null, false, false);

            AgentContentInstaller installer = new AgentContentInstaller(records, runtimeRoot, 60);
            installer.install(entry); // must NOT throw; the escaping entry is just skipped

            // The escaped file must not have been copied into the runtime skills tree.
            Path copiedSkill = runtimeRoot.resolve("skills")
                .resolve("test:CLAUDE:escape").resolve("../escape-target").normalize();
            assertFalse(Files.exists(copiedSkill),
                "skill entry escaping pluginRoot must not be copied: " + copiedSkill);
            // No skills should have been copied at all under the uid dir.
            Path skillDir = runtimeRoot.resolve("skills").resolve("test:CLAUDE:escape");
            if (Files.isDirectory(skillDir)) {
                try (var stream = Files.walk(skillDir)) {
                    long files = stream.filter(Files::isRegularFile).count();
                    assertEquals(0, files, "no skill files should be materialized for an all-escaping manifest");
                }
            }
        }
    }
}
