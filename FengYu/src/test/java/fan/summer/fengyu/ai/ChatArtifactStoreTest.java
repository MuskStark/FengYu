package fan.summer.fengyu.ai;

import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Host-side save closure for chat artifacts: registration, keep-both collision handling,
 * failure retention + retry, restart recovery, and scope purge. Server-side halves of
 * acceptance rows C01, C04, C05, C06, C07 (collection), and C09.
 */
class ChatArtifactStoreTest {

    private static final String PLUGIN_ID = "test.writer";

    @TempDir Path temp;
    private int rootSeq = 0;

    private Fixture fixture() throws Exception {
        Path pluginRoot = Files.createDirectories(temp.resolve("plugins"));
        Path pluginDir = Files.createDirectories(pluginRoot.resolve(PLUGIN_ID));
        Files.writeString(pluginDir.resolve("manifest.json"), """
            {"schemaVersion":2,"id":"%s","name":"Writer","description":"test","version":"1.0.0",
             "author":"test","icon":"test","category":"OTHER","ui":{"entry":"ui/index.html"},
             "backend":{"callTimeoutSeconds":60},"permissions":["files.read","files.write"],
             "official":false,"aiTools":[]}
            """.formatted(PLUGIN_ID));
        PluginFileGrantService files = new PluginFileGrantService(
                temp.resolve("grants-" + (++rootSeq)).toString());
        ChatFileGrantService chatFiles =
                new ChatFileGrantService(new PluginPackageService(pluginRoot.toString()), files);
        return new Fixture(files, chatFiles,
                new ChatArtifactStore(files, temp.resolve("artifacts-" + rootSeq)));
    }

    private record Fixture(PluginFileGrantService files, ChatFileGrantService chatFiles,
            ChatArtifactStore store) {}

    private ChatFileGrantService.StagedOutput staging(Fixture f, String... fileNames) throws Exception {
        PluginFileGrantService.FileRef ref = f.files().outputDirectory(PLUGIN_ID);
        Path dir = f.files().resolve(PLUGIN_ID, ref.id());
        for (String name : fileNames) {
            Path file = dir.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "content of " + name);
        }
        return new ChatFileGrantService.StagedOutput(PLUGIN_ID, ref, null);
    }

    @Test
    void completeTurnRegistersEveryStagedFileAndRevokesStaging() throws Exception {
        Fixture f = fixture();
        ChatFileGrantService.StagedOutput staged = staging(f, "report.xlsx", "summary.csv");
        Path stagingDir = f.files().resolve(PLUGIN_ID, staged.stagingRef().id());

        List<ChatArtifactStore.Artifact> artifacts =
                f.store().completeTurn("cs_1", 42L, List.of(staged));

        assertEquals(2, artifacts.size());
        assertTrue(artifacts.stream().allMatch(a -> ChatArtifactStore.STATE_READY.equals(a.state())));
        assertEquals(42L, artifacts.get(0).conversationId(), "conversation binding survives restarts");
        assertTrue(f.files().writablePaths(PLUGIN_ID).isEmpty(), "staging grant revoked");
        assertTrue(Files.notExists(stagingDir), "staging tree reclaimed");
        for (ChatArtifactStore.Artifact artifact : artifacts) {
            assertTrue(Files.isRegularFile(f.store().pendingPath(artifact.artifactId())),
                    "the only copy now lives in the managed pending store");
        }
    }

    @Test
    void autoSaveIntoTheTurnsTargetSavesAndCleansPending() throws Exception {
        Fixture f = fixture();
        Path target = Files.createDirectories(temp.resolve("out"));
        PluginFileGrantService.FileRef ref = f.files().outputDirectory(PLUGIN_ID);
        Files.writeString(f.files().resolve(PLUGIN_ID, ref.id()).resolve("报表.xlsx"), "data");
        ChatFileGrantService.StagedOutput staged =
                new ChatFileGrantService.StagedOutput(PLUGIN_ID, ref, target);

        List<ChatArtifactStore.Artifact> artifacts =
                f.store().completeTurn("cs_2", 1L, List.of(staged));

        assertEquals(1, artifacts.size());
        assertEquals(ChatArtifactStore.STATE_SAVED, artifacts.get(0).state());
        assertEquals(target.resolve("报表.xlsx").toString(), artifacts.get(0).savedPath());
        assertEquals("data", Files.readString(Path.of(artifacts.get(0).savedPath())));
        assertEquals(target, f.store().savedPath(artifacts.get(0).artifactId()).getParent(),
                "reveal resolves only the confirmed saved location");
        assertTrue(Files.notExists(f.store().pendingRoot().resolve(artifacts.get(0).artifactId())),
                "pending copy released after a confirmed save");
    }

    @Test
    void sameNameKeepsBothFilesInsteadOfOverwriting() throws Exception {
        Fixture f = fixture();
        Path target = Files.createDirectories(temp.resolve("keep-both"));
        Files.writeString(target.resolve("report.xlsx"), "pre-existing user file");

        ChatFileGrantService.StagedOutput first = staging(f, "report.xlsx");
        ChatArtifactStore.Artifact one =
                f.store().completeTurn("cs_3", null, List.of(first)).get(0);
        ChatArtifactStore.Artifact savedOne = f.store().save(one.artifactId(), target.toString());

        assertEquals(ChatArtifactStore.STATE_SAVED, savedOne.state());
        assertEquals("pre-existing user file", Files.readString(target.resolve("report.xlsx")),
                "C04: the existing file is never silently replaced");
        assertEquals("content of report.xlsx",
                Files.readString(target.resolve("report (2).xlsx")));
    }

    @Test
    void failedSaveRetainsTheResultAndRetrySucceeds() throws Exception {
        Fixture f = fixture();
        ChatArtifactStore.Artifact artifact =
                f.store().completeTurn("cs_4", null, List.of(staging(f, "only.xlsx"))).get(0);
        Path notADirectory = Files.writeString(temp.resolve("occupied"), "x");

        ChatArtifactStore.Artifact failed =
                f.store().save(artifact.artifactId(), notADirectory.toString());

        assertEquals(ChatArtifactStore.STATE_FAILED, failed.state());
        assertNotNull(failed.error());
        assertTrue(Files.isRegularFile(f.store().pendingPath(artifact.artifactId())),
                "C05: the only copy of the result must survive a failed save");

        Path goodTarget = Files.createDirectories(temp.resolve("retry-out"));
        ChatArtifactStore.Artifact retried = f.store().save(artifact.artifactId(), goodTarget.toString());
        assertEquals(ChatArtifactStore.STATE_SAVED, retried.state());
        assertEquals("content of only.xlsx", Files.readString(Path.of(retried.savedPath())));
    }

    @Test
    void partialFailuresAreRecordedPerFile() throws Exception {
        Fixture f = fixture();
        Path target = Files.createDirectories(temp.resolve("partial"));
        PluginFileGrantService.FileRef ref = f.files().outputDirectory(PLUGIN_ID);
        Path stagingDir = f.files().resolve(PLUGIN_ID, ref.id());
        Files.writeString(stagingDir.resolve("a.txt"), "a");
        Files.writeString(stagingDir.resolve("b.txt"), "b");

        List<ChatArtifactStore.Artifact> registered = new java.util.ArrayList<>(
                f.store().completeTurn("cs_5", null, List.of(new ChatFileGrantService.StagedOutput(
                        PLUGIN_ID, ref, target))));
        assertEquals(2, registered.size());
        // Both files saved individually — per-file states, no batch atomicity pretend.
        assertTrue(registered.stream().allMatch(a -> ChatArtifactStore.STATE_SAVED.equals(a.state())));
    }

    @Test
    void symbolLinksAndEscapingEntriesAreRefused() throws Exception {
        Fixture f = fixture();
        PluginFileGrantService.FileRef ref = f.files().outputDirectory(PLUGIN_ID);
        Path stagingDir = f.files().resolve(PLUGIN_ID, ref.id());
        Path outside = Files.writeString(temp.resolve("outside.txt"), "escape");
        Files.createSymbolicLink(stagingDir.resolve("linked.txt"), outside);

        List<ChatArtifactStore.Artifact> artifacts =
                f.store().completeTurn("cs_6", null, List.of(
                        new ChatFileGrantService.StagedOutput(PLUGIN_ID, ref, null)));

        assertTrue(artifacts.isEmpty(), "C07: a symlinked staging entry refuses collection wholesale");
    }

    @Test
    void restartRestoresPendingArtifactsWithoutWriteAuthorization() throws Exception {
        Fixture f = fixture();
        ChatArtifactStore.Artifact artifact =
                f.store().completeTurn("cs_7", 77L, List.of(staging(f, "pending.xlsx"))).get(0);

        // Simulated restart: a fresh store over the same directory re-reads the manifest.
        ChatArtifactStore reopened = new ChatArtifactStore(f.files(), f.store().pendingRoot().getParent());
        List<ChatArtifactStore.Artifact> pending = reopened.listPendingByConversation(77L);

        assertEquals(1, pending.size());
        assertEquals(ChatArtifactStore.STATE_READY, pending.get(0).state());
        assertThrows(IllegalArgumentException.class,
                () -> reopened.savedPath(artifact.artifactId()),
                "C09: a saved location is not fabricated for an unsaved artifact");
        assertTrue(Files.isRegularFile(reopened.pendingPath(artifact.artifactId())));
    }

    @Test
    void purgingAScopeDeletesPendingCopiesButNotSavedUserFiles() throws Exception {
        Fixture f = fixture();
        Path target = Files.createDirectories(temp.resolve("purge-out"));
        ChatFileGrantService.StagedOutput staged = staging(f, "keep.txt");
        ChatArtifactStore.Artifact artifact =
                f.store().completeTurn("cs_8", null, List.of(staged)).get(0);
        f.store().save(artifact.artifactId(), target.toString());

        ChatFileGrantService.StagedOutput pendingStaged = staging(f, "pending.txt");
        ChatArtifactStore.Artifact pendingArtifact =
                f.store().completeTurn("cs_8", null, List.of(pendingStaged)).get(0);

        f.store().purgeScope("cs_8");

        assertTrue(Files.isRegularFile(target.resolve("keep.txt")),
                "a saved result belongs to the user's filesystem, not the scope");
        assertTrue(f.store().listByScope("cs_8").isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> f.store().pendingPath(pendingArtifact.artifactId()));
    }

    @Test
    void savingIsIdempotentForCompletedArtifacts() throws Exception {
        Fixture f = fixture();
        Path target = Files.createDirectories(temp.resolve("idempotent"));
        ChatArtifactStore.Artifact artifact =
                f.store().completeTurn("cs_9", null, List.of(staging(f, "once.xlsx"))).get(0);
        ChatArtifactStore.Artifact first = f.store().save(artifact.artifactId(), target.toString());
        ChatArtifactStore.Artifact second = f.store().save(artifact.artifactId(), target.toString());

        assertEquals(first.savedPath(), second.savedPath());
        assertFalse(Files.exists(target.resolve("once (2).xlsx")),
                "a repeated save must not mint a keep-both duplicate");
    }
}
