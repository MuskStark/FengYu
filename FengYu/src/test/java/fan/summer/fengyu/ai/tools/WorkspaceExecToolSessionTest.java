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
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /** A session is private to the conversation that started it: the SAME workspace root
     *  bound to a different conversation must not see or drive the first one's process. */
    @Test
    void sessionIsPrivateToTheConversationThatStartedIt() throws Exception {
        JsonNode start = JSON.readTree(tool.workspaceExec("cat", null, null, true));
        String sessionId = start.path("sessionId").asText();

        WorkspaceContext.set(new WorkspaceContext.Binding(root, 99L));  // same root, other conversation
        JsonNode foreign = JSON.readTree(tool.writeStdin(sessionId, null, null, null));
        assertFalse(foreign.path("success").asBoolean());
        assertTrue(foreign.path("error").asText().contains("Unknown session id"));

        WorkspaceContext.set(new WorkspaceContext.Binding(root, 43L));
        JsonNode own = JSON.readTree(tool.writeStdin(sessionId, "bye\n", 3, true));
        assertTrue(own.path("success").asBoolean(), own.toString());
        assertTrue(own.path("exited").asBoolean());
    }

    /** Regression: one-shot exec output is captured into a BOUNDED head+tail buffer — a
     *  flooding command must never allocate the host into OOM before its timeout. */
    @Test
    void floodOutputIsCapturedBoundedHeadAndTail() throws Exception {
        JsonNode out = JSON.readTree(tool.workspaceExec("yes | head -c 300000", null, 15, null));
        assertTrue(out.path("success").asBoolean(), out.toString());
        String output = out.path("output").asText();
        assertTrue(output.length() <= WorkspaceExecTool.MAX_OUTPUT_CHARS + 80,
                "captured output must stay at the cap, got " + output.length());
        assertTrue(output.contains("characters of output dropped"),
                "the dropped middle must be marked");
        assertTrue(output.startsWith("y"), "the head survives");
    }

    /** Regression: a timed-out command still reports whatever it managed to print — the
     *  partial log usually diagnoses the hang. */
    @Test
    void timedOutCommandStillReportsItsBoundedPartialOutput() throws Exception {
        JsonNode out = JSON.readTree(tool.workspaceExec("echo partial; sleep 30", null, 2, null));
        assertFalse(out.path("success").asBoolean());
        assertTrue(out.path("timedOut").asBoolean());
        assertTrue(out.path("error").asText().contains("timed out"));
        assertTrue(out.path("output").asText().contains("partial"));
    }

    /** Regression (the promised idle-kill contract): a session the conversation NEVER
     *  touches again is reaped by the background sweeper on its own — no future exec
     *  activity required. */
    @Test
    void forgottenIdleSessionIsReapedByTheBackgroundSweeper() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        WorkspaceExecSessions sessions = new WorkspaceExecSessions(clock::get, 20);
        Process shell = new ProcessBuilder("/bin/sh", "-c", "sleep 60").start();
        try {
            WorkspaceExecSessions.Session session = sessions.start(78L, root, shell);
            assertTrue(shell.isAlive());

            clock.addAndGet(WorkspaceExecSessions.IDLE_KILL_MILLIS + 1_000);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!session.exited && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(session.exited, "the sweeper must kill an untouched idle session");
            deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (sessions.get(session.id, 78L, root) != null
                    && System.nanoTime() < deadline) Thread.sleep(20);
            assertNull(sessions.get(session.id, 78L, root),
                    "the killed session leaves the map without any entry-point call");
        } finally {
            WorkspaceExecSessions.killTree(shell);
        }
    }

    /** Regression: session output decodes UTF-8 incrementally — a multibyte character
     *  split across pipe reads must not degrade into replacement characters. */
    @Test
    void sessionDrainDecodesUtf8AcrossByteSplits() throws Exception {
        WorkspaceExecSessions sessions = new WorkspaceExecSessions();
        Process shell = new ProcessBuilder("/bin/sh", "-c", "sleep 30").start();
        try {
            WorkspaceExecSessions.Session session = sessions.start(79L, root, shell);
            byte[] bytes = "你好世界".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.io.InputStream oneByteAtATime = new java.io.ByteArrayInputStream(bytes) {
                @Override
                public int read(byte[] target, int offset, int length) {
                    return super.read(target, offset, Math.min(length, 1));
                }
            };
            WorkspaceExecSessions.drain(oneByteAtATime, session);
            assertEquals("你好世界", sessions.takePending(session));
        } finally {
            WorkspaceExecSessions.killTree(shell);
        }
    }

    /**
     * P1 regression: a backgrounded grandchild must not outlive the kill. Signalling only
     * the {@code /bin/sh -c} wrapper leaves {@code sleep} survivors alive and unfenced —
     * on macOS (no PID namespace) they would never be reaped, defeating the idle-kill
     * contract and the fence.
     */
    @Test
    void killTreeReapsBackgroundedDescendants() throws Exception {
        Process shell = new ProcessBuilder("/bin/sh", "-c", "sleep 30 & sleep 30 & wait")
                .start();
        try {
            java.util.List<ProcessHandle> descendants = java.util.List.of();
            for (int i = 0; i < 40; i++) {
                descendants = shell.descendants().toList();
                if (descendants.size() >= 2) break;
                Thread.sleep(50);
            }
            assertTrue(descendants.size() >= 2,
                    "expected the backgrounded sleeps to spawn, saw " + descendants.size());

            WorkspaceExecSessions.killTree(shell);

            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (shell.isAlive() && System.nanoTime() < deadline) Thread.sleep(25);
            assertFalse(shell.isAlive(), "the shell itself must die");
            for (ProcessHandle descendant : descendants) {
                deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (descendant.isAlive() && System.nanoTime() < deadline) Thread.sleep(25);
                assertFalse(descendant.isAlive(),
                        "backgrounded descendant " + descendant.pid() + " must be reaped too");
            }
        } finally {
            WorkspaceExecSessions.killTree(shell);
        }
    }
}
