package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.ai.workspace.WorkspaceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interactive sessions: workspace_exec interactive=true yields a session id for a live
 * process instead of killing it at the timeout, write_stdin feeds stdin / polls deltas /
 * terminates, and a session is jailed to the workspace root that started it. POSIX-shell
 * only (cat feeding) — the desktop release CI gates on macOS and Linux.
 */
@DisabledOnOs(OS.WINDOWS)
class WorkspaceExecToolSessionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path root;
    @TempDir
    Path otherRoot;

    private WorkspaceExecTool tool;

    @BeforeEach
    void bind() {
        tool = new WorkspaceExecTool();
        WorkspaceContext.set(new WorkspaceContext.Binding(root, 43L));
    }

    @AfterEach
    void unbind() {
        WorkspaceContext.clear();
    }

    @Test
    void quickCommandExitsInsideTheYieldWindowLikeANormalExec() throws Exception {
        JsonNode result = JSON.readTree(tool.workspaceExec("echo hi", null, null, true));
        assertTrue(result.path("success").asBoolean(), result.toString());
        assertTrue(result.path("exited").asBoolean());
        assertEquals(0, result.path("exitCode").asInt());
        assertTrue(result.path("output").asText().contains("hi"));
    }

    @Test
    void sessionFeedsStdinPollsDeltasAndTerminates() throws Exception {
        JsonNode start = JSON.readTree(tool.workspaceExec("cat", null, null, true));
        assertFalse(start.path("exited").asBoolean(), start.toString());
        String sessionId = start.path("sessionId").asText();
        assertFalse(sessionId.isBlank());

        JsonNode echoed = JSON.readTree(tool.writeStdin(sessionId, "hello\n", 5, null));
        assertTrue(echoed.path("success").asBoolean(), echoed.toString());
        assertTrue(echoed.path("output").asText().contains("hello"));
        assertFalse(echoed.path("exited").asBoolean());

        JsonNode poll = JSON.readTree(tool.writeStdin(sessionId, null, 0, null));
        assertTrue(poll.path("success").asBoolean());
        assertTrue(poll.path("output").asText().isEmpty(), "second poll returns only the delta");

        JsonNode killed = JSON.readTree(tool.writeStdin(sessionId, null, 0, true));
        assertTrue(killed.path("exited").asBoolean(), killed.toString());

        // A session is jailed to the workspace that started it: from another root the same
        // id must look unknown, so a second conversation cannot hijack the first's process.
        WorkspaceContext.set(new WorkspaceContext.Binding(otherRoot, 44L));
        JsonNode foreign = JSON.readTree(tool.writeStdin(sessionId, null, null, null));
        assertFalse(foreign.path("success").asBoolean());
        assertTrue(foreign.path("error").asText().contains("Unknown session id"));
    }

    @Test
    void unknownSessionIdIsRejected() throws Exception {
        JsonNode result = JSON.readTree(tool.writeStdin("deadbeefdead", null, null, null));
        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("Unknown session id"));
    }
}
