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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end behavior of the five workspace coding tools against a real temp workspace:
 * line-numbered reads, the read-before-write freshness contract, the edit waterfall, and the
 * capped search primitives with directory exclusions.
 */
class WorkspaceFileToolsTest {

    private static final Long CONVERSATION = 41L;
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path root;

    private WorkspaceFileTools tools;
    private WorkspaceReadState readState;

    @BeforeEach
    void bind() {
        readState = new WorkspaceReadState();
        tools = new WorkspaceFileTools(readState);
        WorkspaceContext.set(new WorkspaceContext.Binding(root, CONVERSATION));
    }

    @AfterEach
    void unbind() {
        WorkspaceContext.clear();
    }

    // ── read_file ────────────────────────────────────────────────────────────────────────

    @Test
    void readFileReturnsNumberedLines() throws IOException {
        Files.writeString(root.resolve("hello.txt"), "first\nsecond\nthird\n");
        JsonNode result = JSON.readTree(tools.readFile("hello.txt", null, null));
        assertTrue(result.path("success").asBoolean());
        assertEquals(3, result.path("totalLines").asInt());
        assertTrue(result.path("content").asText().contains("     2\tsecond"));
        assertFalse(result.path("truncated").asBoolean());
    }

    @Test
    void readFileHonorsOffsetAndLimit() throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 1; i <= 50; i++) body.append("line-").append(i).append('\n');
        Files.writeString(root.resolve("big.txt"), body.toString());
        JsonNode result = JSON.readTree(tools.readFile("big.txt", 10, 5));
        assertTrue(result.path("content").asText().contains("line-10"));
        assertTrue(result.path("content").asText().contains("line-14"));
        assertFalse(result.path("content").asText().contains("line-15\n"));
        assertTrue(result.path("truncated").asBoolean());
        assertEquals(10, result.path("offset").asInt());
    }

    @Test
    void readRejectsBinaryFile() throws IOException {
        Files.write(root.resolve("blob.bin"), new byte[] {0, 1, 2, 0, 3});
        JsonNode result = JSON.readTree(tools.readFile("blob.bin", null, null));
        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("binary"));
    }

    @Test
    void readRejectsEscapeOutsideWorkspace() throws IOException {
        Path outside = Files.createTempDirectory("outside");
        Files.writeString(outside.resolve("secret.txt"), "s");
        JsonNode result = JSON.readTree(tools.readFile("../secret.txt", null, null));
        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("workspace"));
    }

    // ── write_file / freshness ────────────────────────────────────────────────────────────

    @Test
    void writeCreatesNewFileAndParentDirectories() throws IOException {
        JsonNode result = JSON.readTree(tools.writeFile("src/deep/New.java", "class New {}\n", null));
        assertTrue(result.path("success").asBoolean());
        assertTrue(result.path("created").asBoolean());
        assertEquals("class New {}\n", Files.readString(root.resolve("src/deep/New.java")));
    }

    @Test
    void overwriteRequiresPriorRead() throws IOException {
        Files.writeString(root.resolve("a.txt"), "original\n");
        JsonNode rejected = JSON.readTree(tools.writeFile("a.txt", "replaced\n", null));
        assertFalse(rejected.path("success").asBoolean());
        assertTrue(rejected.path("error").asText().contains("read"));

        tools.readFile("a.txt", null, null);
        JsonNode accepted = JSON.readTree(tools.writeFile("a.txt", "replaced\n", null));
        assertTrue(accepted.path("success").asBoolean());
        assertTrue(accepted.path("diff").asText().contains("-original"));
        assertTrue(accepted.path("diff").asText().contains("+replaced"));
        assertFalse(accepted.path("created").asBoolean());
    }

    @Test
    void staleReadBlocksOverwrite() throws IOException {
        Path file = root.resolve("a.txt");
        Files.writeString(file, "v1\n");
        tools.readFile("a.txt", null, null);
        // External editor writes while the model is not looking.
        Files.writeString(file, "v2-tampered\n");
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
        JsonNode result = JSON.readTree(tools.writeFile("a.txt", "v3\n", null));
        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("changed on disk"));
    }

    // ── edit_file ────────────────────────────────────────────────────────────────────────

    @Test
    void editReplacesExactTextOnceByDefault() throws IOException {
        Files.writeString(root.resolve("a.txt"), "one two one two\n");
        tools.readFile("a.txt", null, null);
        JsonNode result = JSON.readTree(tools.editFile("a.txt", "two", "TWO", null));
        assertTrue(result.path("success").asBoolean());
        assertEquals(1, result.path("replacements").asInt());
        assertEquals("one TWO one two\n", Files.readString(root.resolve("a.txt")));
        assertEquals("EXACT", result.path("strategy").asText());
    }

    @Test
    void editReplaceAllTouchesEveryOccurrence() throws IOException {
        Files.writeString(root.resolve("a.txt"), "x alpha x alpha\n");
        tools.readFile("a.txt", null, null);
        JsonNode result = JSON.readTree(tools.editFile("a.txt", "alpha", "beta", true));
        assertEquals(2, result.path("replacements").asInt());
        assertEquals("x beta x beta\n", Files.readString(root.resolve("a.txt")));
    }

    @Test
    void editMatchesThroughReadLineNumberPrefixes() throws IOException {
        Files.writeString(root.resolve("a.txt"), "start\nmiddle\nend\n");
        String read = JSON.readTree(tools.readFile("a.txt", null, null)).path("content").asText();
        // A search string copied straight out of the numbered read output.
        String[] numbered = read.split("\n");
        String copied = String.join("\n", numbered[1], numbered[2]);
        JsonNode result = JSON.readTree(tools.editFile("a.txt", copied, "MIDDLE\nend", null));
        assertTrue(result.path("success").asBoolean());
        assertEquals("LINE_NUMBER_PREFIX_STRIPPED", result.path("strategy").asText());
        assertEquals("start\nMIDDLE\nend\n", Files.readString(root.resolve("a.txt")));
    }

    @Test
    void editWithoutPriorReadIsRejected() throws IOException {
        Files.writeString(root.resolve("b.txt"), "content\n");
        JsonNode result = JSON.readTree(tools.editFile("b.txt", "content", "changed", null));
        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("read"));
    }

    @Test
    void editAfterExternalChangeIsRejected() throws IOException {
        Path file = root.resolve("c.txt");
        Files.writeString(file, "stable\n");
        tools.readFile("c.txt", null, null);
        Files.writeString(file, "changed-externally\n");
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
        JsonNode result = JSON.readTree(tools.editFile("c.txt", "stable", "edited", null));
        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("changed on disk"));
    }

    @Test
    void editStaleViewDetectedThroughMatcherDifference() throws IOException {
        // The file drifted to content the model's old_string no longer matches exactly.
        Files.writeString(root.resolve("d.txt"), "value = 1\n");
        tools.readFile("d.txt", null, null);
        Files.writeString(root.resolve("d.txt"), "value = 2\n");
        JsonNode result = JSON.readTree(tools.editFile("d.txt", "value = 1", "value = 3", null));
        // The mtime check must fire before any fuzzy matching could "helpfully" match.
        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("changed on disk")
                || result.path("error").asText().contains("not found"));
    }

    @Test
    void editMissingTextIsRejectedWithGuidance() throws IOException {
        Files.writeString(root.resolve("e.txt"), "keep\n");
        tools.readFile("e.txt", null, null);
        JsonNode result = JSON.readTree(tools.editFile("e.txt", "absent", "x", null));
        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("not found"));
    }

    // ── grep / glob ──────────────────────────────────────────────────────────────────────

    @Test
    void grepFindsMatchesWithFileAndLine() throws IOException {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/A.java"), "class A {}\n// TODO fix\n");
        Files.writeString(root.resolve("src/B.java"), "class B {}\n// done\n");
        Files.writeString(root.resolve("notes.md"), "# TODO list\n");
        JsonNode result = JSON.readTree(tools.grep("TODO", null, null, null));
        assertTrue(result.path("success").asBoolean());
        assertEquals(2, result.path("count").asInt());
        assertTrue(result.path("matches").toString().contains("src/A.java"));
        assertTrue(result.path("matches").toString().contains("notes.md"));
    }

    @Test
    void grepRespectsGlobFilterAndExcludedDirectories() throws IOException {
        Files.writeString(root.resolve("real.txt"), "needle here\n");
        Files.createDirectories(root.resolve("node_modules/pkg"));
        Files.writeString(root.resolve("node_modules/pkg/dep.js"), "needle noise\n");
        Files.writeString(root.resolve("skipped.md"), "needle but wrong type\n");
        JsonNode result = JSON.readTree(tools.grep("needle", "*.txt", null, null));
        assertEquals(1, result.path("count").asInt());
        assertTrue(result.path("matches").get(0).path("path").asText().contains("real.txt"));
    }

    @Test
    void grepCapsResults() throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 10; i++) body.append("hit ").append(i).append('\n');
        Files.writeString(root.resolve("many.txt"), body.toString());
        JsonNode result = JSON.readTree(tools.grep("hit", null, null, 3));
        assertEquals(3, result.path("count").asInt());
        assertTrue(result.path("truncated").asBoolean());
    }

    @Test
    void globListsMatchingFilesOnly() throws IOException {
        Files.writeString(root.resolve("a.ts"), "1");
        Files.writeString(root.resolve("b.js"), "1");
        Files.createDirectories(root.resolve("node_modules"));
        Files.writeString(root.resolve("node_modules/c.ts"), "1");
        JsonNode result = JSON.readTree(tools.glob("*.ts", null, null));
        assertTrue(result.path("success").asBoolean());
        assertEquals(1, result.path("count").asInt());
        assertEquals("a.ts", result.path("matches").get(0).asText());
    }

    // ── binding discipline ───────────────────────────────────────────────────────────────

    @Test
    void unboundConversationFailsFast() throws IOException {
        WorkspaceContext.clear();
        JsonNode result = JSON.readTree(tools.readFile("whatever.txt", null, null));
        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("workspace"));
    }

    @Test
    void effectMappingDrivesThePermissionPipeline() {
        assertEquals(ToolEffect.READ, tools.effectFor("read_file"));
        assertEquals(ToolEffect.READ, tools.effectFor("grep"));
        assertEquals(ToolEffect.READ, tools.effectFor("glob"));
        assertEquals(ToolEffect.WRITE, tools.effectFor("write_file"));
        assertEquals(ToolEffect.WRITE, tools.effectFor("edit_file"));
    }
}
