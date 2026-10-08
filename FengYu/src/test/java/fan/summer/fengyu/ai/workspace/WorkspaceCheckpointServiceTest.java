package fan.summer.fengyu.ai.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the safety properties with teeth: jail-adjacent workspaces record nothing,
 *  the daily sweep reclaims orphaned conversation dirs without touching fresh ones,
 *  checkpoints are scoped to the workspace root they were captured under, and the
 *  per-conversation cap never silently replaces a path's pre-conversation baseline. */
class WorkspaceCheckpointServiceTest {

    @TempDir
    Path tmp;

    /** The injected checkpoint home keeps every test off the real ~/.fengyu tree. */
    private WorkspaceCheckpointService service() {
        return new WorkspaceCheckpointService(tmp.resolve("checkpoints"));
    }

    @Test
    void workspacesOverlappingTheCheckpointTreeRecordNothing() throws IOException {
        WorkspaceCheckpointService service = service();
        // tmp is an ANCESTOR of tmp/checkpoints/<id>: snapshots would land INSIDE the
        // workspace and become read_file-reachable / writable.
        Path ancestor = tmp;
        service.snapshotBefore(1L, ancestor, ancestor.resolve("some/project/file.txt"));
        assertTrue(service.changes(1L, ancestor).isEmpty(),
                "an ancestor-of-checkpoints workspace must not record snapshots");

        // Ordinary workspace outside the checkpoint tree keeps recording (control case).
        Path ordinary = Files.createDirectories(tmp.resolve("project"));
        Path file = ordinary.resolve("src/A.java");
        service.snapshotBefore(2L, ordinary, file); // file does not exist → creation entry
        assertEquals(1, service.changes(2L, ordinary).size());
    }

    @Test
    void checkpointsFromAPreviousWorkspaceRootAreInvisibleAndInert() throws IOException {
        WorkspaceCheckpointService service = service();
        // Workspace A: edit a file so a baseline snapshot exists.
        Path workspaceA = Files.createDirectories(tmp.resolve("workspace-a/src"));
        Path fileA = workspaceA.resolve("A.java");
        Files.writeString(fileA, "original");
        service.snapshotBefore(9L, workspaceA, fileA);
        Files.writeString(fileA, "changed");

        // Same conversation re-attached to workspace B: A's entries must not appear...
        Path workspaceB = Files.createDirectories(tmp.resolve("workspace-b"));
        assertTrue(service.changes(9L, workspaceB).isEmpty(),
                "a previous workspace's checkpoints must not be listed under the new root");
        // ...and rollback must NOT write A's baseline into B.
        String result = service.rollbackAll(9L, workspaceB);
        assertTrue(result.contains("No checkpoints recorded"), result);
        assertTrue(Files.notExists(workspaceB.resolve("src/A.java")),
                "rollback under the new root must not create a foreign file");
        // ...while re-attaching the SAME root A still sees its history (idempotent re-attach).
        List<WorkspaceCheckpointService.FileChange> changes = service.changes(9L, workspaceA);
        assertEquals(1, changes.size(), "same-root re-attach keeps its checkpoint history");
        assertEquals("restored A.java", service.rollbackFile(9L, workspaceA, "A.java"));
        assertEquals("original", Files.readString(fileA));
    }

    @Test
    void evictionNeverReplacesAPathsPreConversationBaseline() throws IOException {
        WorkspaceCheckpointService service = service();
        Path root = Files.createDirectories(tmp.resolve("project"));
        Path file = root.resolve("a.txt");
        Files.writeString(file, "pre-conversation baseline");
        // Far more writes than MAX_ENTRIES_PER_CONVERSATION, all to the SAME path: the
        // later snapshots must evict, never the first (pre-conversation) one — otherwise
        // rollback silently restores an intermediate state as "the original".
        for (int i = 0; i < WorkspaceCheckpointService.MAX_ENTRIES_PER_CONVERSATION + 50; i++) {
            service.snapshotBefore(11L, root, file);
            Files.writeString(file, "edit " + i);
        }
        assertEquals("restored a.txt", service.rollbackFile(11L, root, "a.txt"));
        assertEquals("pre-conversation baseline", Files.readString(file),
                "rollback must reach the pre-conversation baseline, not an intermediate edit");
    }

    @Test
    void filesCreatedByTheConversationAreCreatedNotReverted() throws IOException {
        WorkspaceCheckpointService service = service();
        Path root = Files.createDirectories(tmp.resolve("project"));
        Path created = root.resolve("new.txt");
        service.snapshotBefore(12L, root, created); // does not exist → creation entry
        Files.writeString(created, "fresh content");

        WorkspaceCheckpointService.FileChange change = service.changes(12L, root).get(0);

        assertTrue(change.created());
        assertFalse(change.reverted(),
                "a created file that still exists is a net change, not a revert");
        assertFalse(change.gone());
    }

    @Test
    void revertedOnlyWhenExistingContentIsBackToItsBaseline() throws IOException {
        WorkspaceCheckpointService service = service();
        Path root = Files.createDirectories(tmp.resolve("project"));
        Path file = root.resolve("a.txt");
        Files.writeString(file, "before");
        service.snapshotBefore(13L, root, file);
        Files.writeString(file, "after");
        assertFalse(service.changes(13L, root).get(0).reverted());
        Files.writeString(file, "before");
        assertTrue(service.changes(13L, root).get(0).reverted());
    }

    @Test
    void sweepDeletesOnlyConversationDirsOlderThanTheCutoff() throws IOException {
        Path stale = Files.createDirectories(tmp.resolve("9001"));
        Files.createDirectories(stale.resolve("1"));
        Files.writeString(stale.resolve("1/file.txt"), "old snapshot");
        Files.setLastModifiedTime(stale,
                java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(86400 * 15)));
        Files.createDirectories(tmp.resolve("9002"));
        Files.writeString(tmp.resolve("9002/kept.txt"), "marker");

        WorkspaceCheckpointService.sweepStale(tmp,
                Instant.now().minus(WorkspaceCheckpointService.SWEEP_AGE));

        assertFalse(Files.exists(stale), "a dir older than the cutoff is swept");
        assertTrue(Files.exists(tmp.resolve("9002")), "a fresh dir survives the sweep");
        assertTrue(Files.exists(tmp.resolve("9002/kept.txt")), "sweep never touches non-dirs");
    }

    @Test
    void sweepOfAMissingHomeIsANoOp() {
        WorkspaceCheckpointService.sweepStale(tmp.resolve("does-not-exist"), Instant.now());
    }

    @Test
    void clearConversationRemovesIndexAndTree() throws IOException {
        WorkspaceCheckpointService service = service();
        Path root = Files.createDirectories(tmp.resolve("project"));
        Path file = root.resolve("a.txt");
        Files.writeString(file, "before");
        service.snapshotBefore(7L, root, file);
        assertEquals(1, service.changes(7L, root).size());
        assertTrue(Files.isDirectory(tmp.resolve("checkpoints").resolve("7")),
                "the snapshot tree exists under the injected home before the clear");

        service.clearConversation(7L);

        assertTrue(service.changes(7L, root).isEmpty(), "index dropped");
        assertTrue(Files.notExists(tmp.resolve("checkpoints").resolve("7")),
                "snapshot tree deleted");
    }
}
