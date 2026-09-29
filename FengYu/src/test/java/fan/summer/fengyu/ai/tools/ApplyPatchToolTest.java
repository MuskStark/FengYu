package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.ai.workspace.WorkspaceContext;
import fan.summer.fengyu.ai.workspace.WorkspaceReadState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end behavior of the multi-file apply_patch tool against a real temp workspace:
 * sequential chunk matching with @@ anchors, add/delete/move hunks, the shared
 * read-before-edit contract, partial-failure semantics (earlier hunks stay applied),
 * and the jail/duplicate pre-verification.
 */
class ApplyPatchToolTest {

    private static final Long CONVERSATION = 42L;
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path root;

    private WorkspaceReadState readState;
    private WorkspaceFileTools fileTools;
    private ApplyPatchTool tool;

    @BeforeEach
    void bind() {
        readState = new WorkspaceReadState();
        fileTools = new WorkspaceFileTools(readState);
        tool = new ApplyPatchTool(readState);
        WorkspaceContext.set(new WorkspaceContext.Binding(root, CONVERSATION));
    }

    @AfterEach
    void unbind() {
        WorkspaceContext.clear();
    }

    private String read(String path) {
        return fileTools.readFile(path, null, null);
    }

    private static String content(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    // ── Update ───────────────────────────────────────────────────────────────────────────

    @Test
    void appliesContextPatchWithAnchor() throws IOException {
        Files.writeString(root.resolve("app.py"), "def example():\n    return 1\n");
        read("app.py");

        JsonNode result = JSON.readTree(tool.applyPatch("""
                *** Begin Patch
                *** Update File: app.py
                @@ def example():
                -    return 1
                +    return 2
                *** End Patch
                """));
        assertTrue(result.path("success").asBoolean(), result.toString());
        assertEquals("def example():\n    return 2\n", content(root.resolve("app.py")));
        assertEquals("M app.py", result.path("files").get(0).asText());
    }

    @Test
    void anchorDisambiguatesRepeatedBlocks() throws IOException {
        Files.writeString(root.resolve("twice.py"),
                "def one():\n    return 1\n\ndef two():\n    return 1\n");
        read("twice.py");

        JsonNode result = JSON.readTree(tool.applyPatch("""
                *** Begin Patch
                *** Update File: twice.py
                @@ def two():
                -    return 1
                +    return 2
                *** End Patch
                """));
        assertTrue(result.path("success").asBoolean(), result.toString());
        assertEquals("def one():\n    return 1\n\ndef two():\n    return 2\n",
                content(root.resolve("twice.py")));
    }

    @Test
    void sequentialChunksApplyInOrder() throws IOException {
        Files.writeString(root.resolve("a.txt"), "one\ntwo\nthree\nfour\n");
        read("a.txt");

        JsonNode result = JSON.readTree(tool.applyPatch("""
                *** Begin Patch
                *** Update File: a.txt
                @@ one
                -two
                +TWO
                @@ three
                -four
                +FOUR
                *** End Patch
                """));
        assertTrue(result.path("success").asBoolean(), result.toString());
        assertEquals("one\nTWO\nthree\nFOUR\n", content(root.resolve("a.txt")));
    }

    @Test
    void indentationDriftStillMatchesViaTrimPass() throws IOException {
        Files.writeString(root.resolve("deep.py"), "def f():\n        return 1\n");
        read("deep.py");

        JsonNode result = JSON.readTree(tool.applyPatch("""
                *** Begin Patch
                *** Update File: deep.py
                -    return 1
                +    return 2
                *** End Patch
                """));
        assertTrue(result.path("success").asBoolean(), result.toString());
        assertEquals("def f():\n    return 2\n", content(root.resolve("deep.py")));
    }

    @Test
    void preservesMissingTrailingNewline() throws IOException {
        Files.writeString(root.resolve("noeol.txt"), "a\nb");
        read("noeol.txt");

        JsonNode result = JSON.readTree(tool.applyPatch("""
                *** Begin Patch
                *** Update File: noeol.txt
                -b
                +c
                *** End Patch
                """));
        assertTrue(result.path("success").asBoolean(), result.toString());
        assertEquals("a\nc", content(root.resolve("noeol.txt")));
    }

    @Test
    void endOfFileAnchorMatchesTheTailNotTheFirstOccurrence() throws IOException {
        // "-x" matches line 0 and line 1; only the EOF anchor forces the tail one.
        Files.writeString(root.resolve("dup.txt"), "x\nx\n");
        read("dup.txt");

        JsonNode result = JSON.readTree(tool.applyPatch("""
                *** Begin Patch
                *** Update File: dup.txt
                -x
                +tail-was-here
                *** End of File
                *** End Patch
                """));
        assertTrue(result.path("success").asBoolean(), result.toString());
        assertEquals("x\ntail-was-here\n", content(root.resolve("dup.txt")));
    }

    // ── Add / Delete / Move ──────────────────────────────────────────────────────────────

    @Test
    void multiFilePatchAddsUpdatesAndDeletesInOneCall() throws IOException {
        Files.writeString(root.resolve("app.py"), "x = 1\n");
        Files.writeString(root.resolve("old.txt"), "bye\n");
        read("app.py");
        read("old.txt");

        JsonNode result = JSON.readTree(tool.applyPatch("""
                *** Begin Patch
                *** Update File: app.py
                -x = 1
                +x = 2
                *** Add File: new.txt
                +hello
                +
                +world
                *** Delete File: old.txt
                *** End Patch
                """));
        assertTrue(result.path("success").asBoolean(), result.toString());
        assertEquals("x = 2\n", content(root.resolve("app.py")));
        assertEquals("hello\n\nworld\n", content(root.resolve("new.txt")));
        assertFalse(Files.exists(root.resolve("old.txt")));
        assertEquals(3, result.path("files").size());
        assertTrue(result.path("summary").asText().contains("A new.txt"));
    }

    @Test
    void moveHunkAppliesEditsThenRenames() throws IOException {
        Files.writeString(root.resolve("a.py"), "def f():\n    return 1\n");
        read("a.py");

        JsonNode result = JSON.readTree(tool.applyPatch("""
                *** Begin Patch
                *** Update File: a.py
                *** Move to: b.py
                -    return 1
                +    return 2
                *** End Patch
                """));
        assertTrue(result.path("success").asBoolean(), result.toString());
        assertFalse(Files.exists(root.resolve("a.py")));
        assertEquals("def f():\n    return 2\n", content(root.resolve("b.py")));
        assertEquals("M a.py → b.py", result.path("files").get(0).asText());
    }

    // ── failure semantics ────────────────────────────────────────────────────────────────

    @Test
    void failedHunkKeepsEarlierHunksAppliedAndReportsExpectedLines() throws IOException {
        Files.writeString(root.resolve("first.txt"), "keep\n");
        Files.writeString(root.resolve("second.txt"), "actual\n");
        read("first.txt");
        read("second.txt");

        JsonNode result = JSON.readTree(tool.applyPatch("""
                *** Begin Patch
                *** Update File: first.txt
                -keep
                +kept!
                *** Update File: second.txt
                -this context does not exist
                +whatever
                *** End Patch
                """));
        assertFalse(result.path("success").asBoolean());
        assertEquals("kept!\n", content(root.resolve("first.txt")));
        assertEquals("actual\n", content(root.resolve("second.txt")));
        JsonNode failure = result.path("failures").get(0);
        assertEquals("second.txt", failure.path("path").asText());
        assertTrue(failure.path("error").asText().contains(
                "Failed to find expected lines in second.txt"));
    }

    @Test
    void missingAnchorFailsWithTheAnchorName() throws IOException {
        Files.writeString(root.resolve("x.py"), "a\n");
        read("x.py");

        JsonNode result = JSON.readTree(tool.applyPatch("""
                *** Begin Patch
                *** Update File: x.py
                @@ def nonexistent():
                -a
                +b
                *** End Patch
                """));
        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("failures").get(0).path("error").asText()
                .contains("Failed to find context 'def nonexistent():'"));
    }

    @Test
    void duplicateTargetsAreRejectedBeforeAnythingApplies() throws IOException {
        Files.writeString(root.resolve("dup.txt"), "a\n");
        read("dup.txt");

        JsonNode result = JSON.readTree(tool.applyPatch("""
                *** Begin Patch
                *** Update File: dup.txt
                -a
                +b
                *** Update File: dup.txt
                -b
                +c
                *** End Patch
                """));
        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("multiple operations target"));
        assertEquals("a\n", content(root.resolve("dup.txt")));
    }

    @Test
    void parseErrorsComeBackAsToolErrors() throws Exception {
        JsonNode result = JSON.readTree(tool.applyPatch("not a patch"));
        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("parse failed"));
    }

    // ── read-before-edit contract and jail ───────────────────────────────────────────────

    @Test
    void updateRequiresAFreshRead() throws IOException {
        Files.writeString(root.resolve("locked.py"), "a\n");
        JsonNode result = JSON.readTree(tool.applyPatch("""
                *** Begin Patch
                *** Update File: locked.py
                -a
                +b
                *** End Patch
                """));
        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("failures").get(0).path("error").asText()
                .contains("Read the file with read_file"));
    }

    @Test
    void staleReadIsRejected() throws IOException {
        Files.writeString(root.resolve("stale.py"), "a\n");
        read("stale.py");
        Files.writeString(root.resolve("stale.py"), "changed externally\n");
        Files.setLastModifiedTime(root.resolve("stale.py"),
                FileTime.fromMillis(System.currentTimeMillis() + 5_000));

        JsonNode result = JSON.readTree(tool.applyPatch("""
                *** Begin Patch
                *** Update File: stale.py
                -a
                +b
                *** End Patch
                """));
        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("failures").get(0).path("error").asText()
                .contains("changed on disk"));
    }

    @Test
    void addNewFileNeedsNoReadButOverwriteDoes() throws Exception {
        JsonNode created = JSON.readTree(tool.applyPatch("""
                *** Begin Patch
                *** Add File: fresh.txt
                +content
                *** End Patch
                """));
        assertTrue(created.path("success").asBoolean(), created.toString());

        // A file created outside the read/write contract has no read state: an Add File
        // hunk that would REPLACE it must demand a read first (write_file semantics).
        Files.writeString(root.resolve("external.txt"), "made by the user\n", StandardCharsets.UTF_8);
        JsonNode overwrite = JSON.readTree(tool.applyPatch("""
                *** Begin Patch
                *** Add File: external.txt
                +different
                *** End Patch
                """));
        assertFalse(overwrite.path("success").asBoolean());
        assertTrue(overwrite.path("failures").get(0).path("error").asText()
                .contains("Read the file"));
    }

    @Test
    void pathsOutsideTheWorkspaceAreRejected() throws Exception {
        JsonNode result = JSON.readTree(tool.applyPatch("""
                *** Begin Patch
                *** Add File: ../escape.txt
                +nope
                *** End Patch
                """));
        assertFalse(result.path("success").asBoolean());
    }
}
