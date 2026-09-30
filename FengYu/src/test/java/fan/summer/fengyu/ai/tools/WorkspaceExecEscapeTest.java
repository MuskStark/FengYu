package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.ai.AiStreamCallback;
import fan.summer.fengyu.ai.AiToolCall;
import fan.summer.fengyu.ai.sandbox.PermissionProfile;
import fan.summer.fengyu.ai.workspace.WorkspaceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * S4 end to end (real macOS fence): a sandboxed write outside the writable root is
 * denied by the OS, the escape approval card is requested (escape:true + command +
 * denial excerpt), approving retries the command ONCE unfenced (real full-access run,
 * audit carries {@code escaped:true}), and rejecting answers the model with the denial
 * plus the user's feedback while the tool result keeps the ordinary shape (the turn
 * continues). Non-sandbox failures never trigger the escape path.
 */
class WorkspaceExecEscapeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path root;

    private WorkspaceContext.Binding binding;
    private Path outsideWorkspace;

    @BeforeEach
    void bind() throws Exception {
        Files.createDirectories(root);
        binding = new WorkspaceContext.Binding(root, null);
        WorkspaceContext.set(binding);
        // OUTSIDE the writable root — a sibling of the JUnit temp dir, cleaned up after.
        outsideWorkspace = Files.createDirectories(
                root.getParent().resolve("escape-outside-" + java.util.UUID.randomUUID()));
    }

    @AfterEach
    void cleanupOutside() {
        if (outsideWorkspace != null) {
            try (var walk = Files.walk(outsideWorkspace)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> path.toFile().delete());
            } catch (Exception ignored) { }
        }
    }

    @AfterEach
    void unbind() {
        WorkspaceContext.clear();
        ToolApprovalContext.clear();
    }

    /** The tool with the workspace-write tier forced on (tests bypass settings storage). */
    private WorkspaceExecTool fencedTool() {
        return new WorkspaceExecTool() {
            @Override
            protected PermissionProfile sandboxProfile(Path workspaceRoot) {
                return new PermissionProfile.WorkspaceWrite(workspaceRoot, List.of(workspaceRoot), false);
            }
        };
    }

    /** Captures the approval request and answers it synchronously with the scripted decision. */
    private static AiStreamCallback approving(ChatToolApprovalGate.Decision decision,
            AtomicReference<AiToolCall> seen) {
        return new AiStreamCallback() {
            @Override public void onToken(String fragment) { }
            @Override
            public void onToolApprovalRequired(String approvalId, AiToolCall toolCall, Instant expiresAt) {
                seen.set(toolCall);
                ChatToolApprovalGate localGate = ToolApprovalContext.gate();
                localGate.resolve(decision, approvalId);
            }
        };
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void approvedEscapeRetriesUnfencedAndSucceeds() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/sandbox-exec")), "sandbox-exec present");
        ChatToolApprovalGate gate = new ChatToolApprovalGate();
        AtomicReference<AiToolCall> seen = new AtomicReference<>();
        ToolApprovalContext.set(gate, approving(ChatToolApprovalGate.Decision.approveOnce(), seen));

        String result = fencedTool().workspaceExec(
                "echo escaped > " + outsideWorkspace.resolve("proof.txt"), null, 30, null);

        JsonNode out = JSON.readTree(result);
        assertTrue(out.path("success").asBoolean(), result);
        assertTrue(Files.exists(outsideWorkspace.resolve("proof.txt")), "the unfenced retry really ran");
        assertEquals(Boolean.TRUE, out.path("sandbox").path("escaped").asBoolean()
                ? Boolean.TRUE : null, "audit marks the escape");
        assertEquals("none", out.path("sandbox").path("backend").asText());

        JsonNode args = seen.get().arguments() == null ? null
                : JSON.valueToTree(seen.get().arguments());
        assertEquals(Boolean.TRUE, args.path("escape").asBoolean());
        assertTrue(args.path("command").asText().contains("proof.txt"));
        assertTrue(args.path("denialReason").asText().toLowerCase().contains("operation not permitted"),
                args.path("denialReason").asText());
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void rejectedEscapeFeedsTheDenialBackAndKeepsTheResultShape() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/sandbox-exec")), "sandbox-exec present");
        ChatToolApprovalGate gate = new ChatToolApprovalGate();
        AtomicReference<AiToolCall> seen = new AtomicReference<>();
        ToolApprovalContext.set(gate,
                approving(ChatToolApprovalGate.Decision.rejectOnce("use the workspace instead"), seen));

        String result = fencedTool().workspaceExec(
                "echo no > " + outsideWorkspace.resolve("never.txt"), null, 30, null);

        JsonNode out = JSON.readTree(result);
        assertFalse(out.path("success").asBoolean());
        assertFalse(Files.exists(outsideWorkspace.resolve("never.txt")));
        assertTrue(out.path("output").asText().contains("declined to run it unfenced"), result);
        assertTrue(out.path("output").asText().contains("use the workspace instead"),
                "the user's feedback reaches the model");
        assertEquals(Boolean.TRUE, out.path("sandbox").path("escapeDenied").asBoolean()
                ? Boolean.TRUE : null);
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void nonSandboxFailuresNeverTriggerTheEscapePath() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/sandbox-exec")), "sandbox-exec present");
        ChatToolApprovalGate gate = new ChatToolApprovalGate();
        AtomicReference<AiToolCall> seen = new AtomicReference<>();
        ToolApprovalContext.set(gate, approving(ChatToolApprovalGate.Decision.approveOnce(), seen));

        // exit 127 = command not found — the command's own failure, quick-rejected by the
        // detector; no approval may be requested.
        String result = fencedTool().workspaceExec("definitely-not-a-command-xyz", null, 30, null);
        JsonNode out = JSON.readTree(result);
        assertFalse(out.path("success").asBoolean());
        assertNull(seen.get(), "no escape approval for a non-sandbox failure");
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.MAC)
    void insideWritesNeverAskForEscape() throws Exception {
        // No ToolApprovalContext installed: if the escape path fired, awaitEscapeApproval
        // would be skipped (no gate) — but a write INSIDE the fence succeeds outright.
        ToolApprovalContext.clear();
        String result = fencedTool().workspaceExec("echo ok > inside.txt", null, 30, null);
        JsonNode out = JSON.readTree(result);
        assertTrue(out.path("success").asBoolean(), result);
        assertTrue(Files.exists(root.resolve("inside.txt")));
        assertEquals("sandbox-exec", out.path("sandbox").path("backend").asText());
    }
}
