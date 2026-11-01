package fan.summer.fengyu.ai.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The v4 file protocol: one file per fact with frontmatter, MEMORY.md index as the only
 * prompt surface, id sanitization, and index capping.
 */
class AiMemoryFilesTest {

    @TempDir
    Path root;

    @Test
    void writePersistsFileAndFeedsIndex() {
        AiMemoryFiles files = new AiMemoryFiles(root);
        files.write("prefers-dark-ui", "The user prefers dark UI themes", "user", "manual",
                "The user prefers dark UI themes in every app.", List.of("ui"));
        assertTrue(root.resolve("prefers-dark-ui.md").toFile().isFile());
        String index = files.indexMarkdown();
        assertTrue(index.contains("prefers-dark-ui"));
        assertTrue(index.contains("dark UI"));
        assertFalse(index.contains("every app"));   // bodies never enter the index
        assertEquals(1, files.size());
    }

    @Test
    void reloadRecoversFactsFromDisk() {
        AiMemoryFiles first = new AiMemoryFiles(root);
        first.write("fact-a", "Fact A", "user", "manual", "Body A", List.of());
        // A fresh instance over the same directory is the prompt-surface of a NEW turn:
        // frontmatter is the persistence format, the constructor the loader.
        AiMemoryFiles second = new AiMemoryFiles(root);
        assertEquals(1, second.size());
        assertTrue(second.indexMarkdown().contains("Fact A"));
    }

    @Test
    void removeDropsFileAndIndexEntry() {
        AiMemoryFiles files = new AiMemoryFiles(root);
        files.write("fact-b", "Fact B", "user", "manual", "Body B", List.of());
        assertTrue(files.remove("fact-b"));
        assertEquals(0, files.size());
        assertFalse(root.resolve("fact-b.md").toFile().isFile());
    }

    @Test
    void unsafeIdsAreSanitizedIntoFileNames() {
        AiMemoryFiles files = new AiMemoryFiles(root);
        files.write("../../etc/passwd", "Escaping id", "user", "manual", "Body", List.of());
        assertTrue(root.resolve("..-..-etc-passwd.md").toFile().isFile());
        assertFalse(root.resolve("etc").toFile().isFile());
    }

    @Test
    void emptyStoreYieldsEmptyIndex() {
        assertEquals("", new AiMemoryFiles(root).indexMarkdown());
    }
}
