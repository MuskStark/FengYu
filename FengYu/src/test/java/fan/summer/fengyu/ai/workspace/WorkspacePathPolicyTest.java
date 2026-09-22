package fan.summer.fengyu.ai.workspace;

import fan.summer.fengyu.ai.workspace.WorkspacePathPolicy.EscapeException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The workspace path jail: relative resolution, absolute-inside acceptance, escape rejection. */
class WorkspacePathPolicyTest {

    @TempDir
    Path root;

    @Test
    void relativePathResolvesAgainstRoot() throws IOException {
        Files.createDirectories(root.resolve("src"));
        Path file = Files.writeString(root.resolve("src/App.java"), "class App {}");
        assertEquals(file.toRealPath(), WorkspacePathPolicy.resolve(root, "src/App.java"));
    }

    @Test
    void absoluteInsideRootIsAccepted() throws IOException {
        Path file = Files.writeString(root.resolve("notes.txt"), "x");
        assertEquals(file.toRealPath(),
                WorkspacePathPolicy.resolve(root, file.toAbsolutePath().toString()));
    }

    @Test
    void nonExistingTailIsKeptForWrites() throws IOException {
        Path resolved = WorkspacePathPolicy.resolve(root, "new/dir/File.java");
        assertTrue(resolved.startsWith(root.toRealPath()));
        assertTrue(resolved.endsWith(Path.of("new/dir/File.java")));
    }

    @Test
    void parentTraversalIsRejected() {
        assertThrows(EscapeException.class, () -> WorkspacePathPolicy.resolve(root, "../outside.txt"));
        assertThrows(EscapeException.class,
                () -> WorkspacePathPolicy.resolve(root, "src/../../outside.txt"));
    }

    @Test
    void absoluteOutsideRootIsRejected() {
        assertThrows(EscapeException.class,
                () -> WorkspacePathPolicy.resolve(root, "/etc/passwd"));
        assertThrows(EscapeException.class, () -> WorkspacePathPolicy.resolve(root, ".."));
    }

    @Test
    void symlinkInsideTreeCannotEscape() throws IOException {
        Path outside = Files.createTempDirectory("outside");
        Files.writeString(outside.resolve("secret.txt"), "s");
        Path link = root.resolve("link");
        Files.createSymbolicLink(link, outside);
        assertThrows(EscapeException.class,
                () -> WorkspacePathPolicy.resolve(root, "link/secret.txt"));
    }

    @Test
    void blankPathIsRejected() {
        assertThrows(EscapeException.class, () -> WorkspacePathPolicy.resolve(root, "  "));
    }

    @Test
    void displayUsesWorkspaceRelativeForm() throws IOException {
        Files.createDirectories(root.resolve("src"));
        Path file = Files.writeString(root.resolve("src/main.rs"), "fn main() {}");
        assertEquals("src/main.rs", WorkspacePathPolicy.display(root, file));
    }
}
