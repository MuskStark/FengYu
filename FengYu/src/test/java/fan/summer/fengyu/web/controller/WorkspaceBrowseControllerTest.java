package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.workspace.WorkspaceContext;
import fan.summer.fengyu.ai.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Read-only contract of {@code WorkspaceBrowseController}: the tree/file surface mirrors the
 * model-facing {@code read_file} caps and the {@code WorkspacePathPolicy} jail, so the chat view's
 * side panel can never see (or reach) more than the coding tools themselves can.
 */
class WorkspaceBrowseControllerTest {

    private final WorkspaceService workspaces = mock(WorkspaceService.class);

    @TempDir
    Path root;

    private WorkspaceBrowseController controller() throws IOException {
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("src/main/java/App.java"), "public class App {}\n");
        Files.writeString(root.resolve("readme.md"), "# hi\r\nsecond line\r\n");
        Files.createDirectories(root.resolve("node_modules/left-pad"));
        Files.writeString(root.resolve("node_modules/left-pad/index.js"), "module.exports = 1;\n");
        Files.writeString(root.resolve("blob.bin"), new String(new char[64]).replace('\0', 'x')
                + "\0" + "tail");
        // Production roots are stored canonical (WorkspaceService.setWorkspace → toRealPath);
        // @TempDir hands out the pre-symlink-collapse form, so canonicalize like the service does.
        when(workspaces.bindingFor(7L)).thenReturn(
                new WorkspaceContext.Binding(root.toRealPath(), 7L));
        return new WorkspaceBrowseController(workspaces);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nodes(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("nodes");
    }

    private static Optional<Map<String, Object>> find(List<Map<String, Object>> nodes, String path) {
        return nodes.stream().filter(n -> path.equals(n.get("path"))).findFirst();
    }

    @Test
    void treeListsRelativePathsAndFlagsDirectories() throws IOException {
        Map<String, Object> body = controller().tree(7L).getBody();

        assertEquals(root.toRealPath().toString(), body.get("workspaceRoot"));
        assertEquals(false, body.get("truncated"));
        assertTrue(find(nodes(body), "src/main/java/App.java").isPresent());
        assertEquals(true, find(nodes(body), "src").get().get("dir"));
        assertEquals(false, find(nodes(body), "readme.md").get().get("dir"));
        assertEquals(19L, find(nodes(body), "readme.md").get().get("size"));
    }

    @Test
    void treeSkipsTraversalExcludedDirectories() throws IOException {
        Map<String, Object> body = controller().tree(7L).getBody();

        assertTrue(find(nodes(body), "node_modules").isEmpty());
        assertTrue(nodes(body).stream().noneMatch(n -> String.valueOf(n.get("path")).startsWith("node_modules/")));
    }

    @Test
    void treeIsNotFoundWithoutAnAttachedWorkspace() {
        when(workspaces.bindingFor(8L)).thenReturn(null);

        assertEquals(404, new WorkspaceBrowseController(workspaces).tree(8L).getStatusCode().value());
        assertEquals(404, new WorkspaceBrowseController(workspaces).file(8L, "a.txt").getStatusCode().value());
    }

    @Test
    void fileReturnsNormalizedTextContent() throws IOException {
        Map<String, Object> body = controller().file(7L, "readme.md").getBody();

        assertEquals("readme.md", body.get("path"));
        assertEquals("# hi\nsecond line\n", body.get("content"));
        assertEquals(19L, body.get("size"));
        assertFalse(body.containsKey("binary"));
        assertFalse(body.containsKey("tooLarge"));
    }

    @Test
    void fileResolvesRelativeAndAbsoluteInsidePaths() throws IOException {
        Map<String, Object> body = controller().file(7L, "src/main/java/App.java").getBody();
        assertEquals("src/main/java/App.java", body.get("path"));
        assertEquals("public class App {}\n", body.get("content"));
    }

    @Test
    void fileRejectsPathsEscapingTheRoot() throws IOException {
        WorkspaceBrowseController controller = controller();

        assertThrows(IllegalArgumentException.class, () -> controller.file(7L, "../../etc/passwd"));
    }

    @Test
    void fileRejectsDirectoriesAndMissingFiles() throws IOException {
        WorkspaceBrowseController controller = controller();

        assertThrows(IllegalArgumentException.class, () -> controller.file(7L, "src"));
        assertThrows(IllegalArgumentException.class, () -> controller.file(7L, "nope.txt"));
    }

    @Test
    void fileFlagsBinaryContentInsteadOfReturningIt() throws IOException {
        Map<String, Object> body = controller().file(7L, "blob.bin").getBody();

        assertEquals(true, body.get("binary"));
        assertFalse(body.containsKey("content"));
    }

    @Test
    void fileFlagsOversizedFiles() throws IOException {
        Files.writeString(root.resolve("huge.txt"),
                "a".repeat((int) WorkspaceBrowseController.MAX_PREVIEW_BYTES + 1));
        Map<String, Object> body = controller().file(7L, "huge.txt").getBody();

        assertEquals(true, body.get("tooLarge"));
        assertFalse(body.containsKey("content"));
    }
}
