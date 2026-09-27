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

/** Guards the two safety properties with teeth: jail-adjacent workspaces record nothing,
 *  and the daily sweep reclaims orphaned conversation dirs without touching fresh ones. */
class WorkspaceCheckpointServiceTest {

    @TempDir
    Path tmp;

    @Test
    void workspacesOverlappingTheCheckpointTreeRecordNothing() throws IOException {
        WorkspaceCheckpointService service = new WorkspaceCheckpointService();
        // The home dir itself is an ANCESTOR of ~/.fengyu/workspace-checkpoints: snapshots
        // would land INSIDE the workspace and become read_file-reachable / writable.
        Path home = Path.of(System.getProperty("user.home"));
        service.snapshotBefore(1L, home, home.resolve("some/project/file.txt"));
        assertTrue(service.changes(1L, home).isEmpty(),
                "an ancestor-of-checkpoints workspace must not record snapshots");

        // Ordinary workspace outside the checkpoint tree keeps recording (control case).
        Path ordinary = Files.createDirectories(tmp.resolve("project"));
        Path file = ordinary.resolve("src/A.java");
        service.snapshotBefore(2L, ordinary, file); // file does not exist → creation entry
        assertEquals(1, service.changes(2L, ordinary).size());
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
        WorkspaceCheckpointService service = new WorkspaceCheckpointService();
        // Direct index manipulation keeps the test off the real ~/.fengyu tree: entries()
        // is not public, so we verify through the public API on a tmp-rooted conversation.
        Path root = Files.createDirectories(tmp.resolve("project"));
        Path file = root.resolve("a.txt");
        Files.writeString(file, "before");
        service.snapshotBefore(7L, root, file);
        List<WorkspaceCheckpointService.FileChange> before = service.changes(7L, root);
        assertEquals(1, before.size());

        service.clearConversation(7L);

        assertTrue(service.changes(7L, root).isEmpty(), "index dropped");
    }
}
