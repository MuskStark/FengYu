package fan.summer.fengyu.ai.workspace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The workspace prompt section: appended only while a workspace is bound, carrying the
 * coding discipline (edit strategy, dirty-workspace protection, verification workflow,
 * path:line citations) and the workspace's own AGENTS.md instructions, capped.
 */
class WorkspacePromptAppenderTest {

    @TempDir
    Path root;

    @AfterEach
    void unbind() {
        WorkspaceContext.clear();
    }

    @Test
    void unboundConversationsKeepThePromptUnchanged() {
        assertEquals("base persona", WorkspacePromptAppender.append("base persona"));
    }

    @Test
    void boundConversationsGainTheCodingDiscipline() {
        WorkspaceContext.set(new WorkspaceContext.Binding(root, 7L));
        String prompt = WorkspacePromptAppender.append("base persona");
        assertTrue(prompt.startsWith("base persona"));
        assertTrue(prompt.contains("## Workspace"));
        assertTrue(prompt.contains("apply_patch"), "edit strategy names the multi-file tool");
        assertTrue(prompt.contains("NEVER revert changes"), "dirty-workspace protection");
        assertTrue(prompt.contains("destructive git commands"),
                "destructive git commands are named");
        assertTrue(prompt.contains("narrowest check first"), "verification workflow");
        assertTrue(prompt.contains("path/to/file.java:123"), "file citation convention");
        assertTrue(prompt.contains("interactive=true"), "interactive sessions are advertised");
    }

    @Test
    void projectInstructionsAreAppendedAndCapped() throws Exception {
        Files.writeString(root.resolve("AGENTS.md"), "Use spaces. Never commit generated files.");
        WorkspaceContext.set(new WorkspaceContext.Binding(root, 7L));
        String prompt = WorkspacePromptAppender.append("base");
        assertTrue(prompt.contains("## Project instructions (AGENTS.md)"));
        assertTrue(prompt.contains("Use spaces."));

        Files.writeString(root.resolve("AGENTS.md"), "x".repeat(20_000));
        String capped = WorkspacePromptAppender.append("base");
        assertTrue(capped.contains("project instructions truncated at "
                + WorkspacePromptAppender.MAX_PROJECT_INSTRUCTIONS_CHARS));
        assertFalse(capped.contains("x".repeat(17_000)), "the cap actually bounds the prompt");
    }

    @Test
    void fengyuMdIsTheFallbackInstructionFile() throws Exception {
        Files.writeString(root.resolve("FENGYU.md"), "House style: hexagonal architecture.");
        WorkspaceContext.set(new WorkspaceContext.Binding(root, 7L));
        String prompt = WorkspacePromptAppender.append("base");
        assertTrue(prompt.contains("House style"));
    }
}
