package fan.summer.fengyu.ai.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Read-state scoping: per-conversation isolation, invalidation, and bounded memory. */
class WorkspaceReadStateTest {

    @TempDir
    Path root;

    @Test
    void readsAreScopedPerConversation() throws Exception {
        WorkspaceReadState state = new WorkspaceReadState();
        Path file = Files.writeString(root.resolve("a.txt"), "x");
        state.recordRead(1L, file, null);
        assertNotNull(state.readEntry(1L, file));
        assertNull(state.readEntry(2L, file));
    }

    @Test
    void invalidateRemovesSingleEntry() throws Exception {
        WorkspaceReadState state = new WorkspaceReadState();
        Path file = Files.writeString(root.resolve("a.txt"), "x");
        state.recordRead(1L, file, FileTime.fromMillis(1));
        state.invalidate(1L, file);
        assertNull(state.readEntry(1L, file));
    }

    @Test
    void clearConversationDropsEverything() throws Exception {
        WorkspaceReadState state = new WorkspaceReadState();
        Path a = Files.writeString(root.resolve("a.txt"), "a");
        Path b = Files.writeString(root.resolve("b.txt"), "b");
        state.recordRead(1L, a, null);
        state.recordRead(1L, b, null);
        state.clearConversation(1L);
        assertNull(state.readEntry(1L, a));
        assertNull(state.readEntry(1L, b));
    }

    @Test
    void oldestReadsEvictedPastTheCap() throws Exception {
        WorkspaceReadState state = new WorkspaceReadState();
        Path first = Files.writeString(root.resolve("000.txt"), "first");
        state.recordRead(1L, first, null);
        for (int i = 1; i <= WorkspaceReadState.MAX_FILES_PER_CONVERSATION; i++) {
            state.recordRead(1L, root.resolve("f" + i + ".txt"), null);
        }
        assertNull(state.readEntry(1L, first));
        assertNotNull(state.readEntry(1L, root.resolve("f" + WorkspaceReadState.MAX_FILES_PER_CONVERSATION + ".txt")));
    }
}
