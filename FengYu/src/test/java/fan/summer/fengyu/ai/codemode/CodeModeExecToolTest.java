package fan.summer.fengyu.ai.codemode;

import fan.summer.fengyu.ai.AiStreamCallback;
import fan.summer.fengyu.ai.AiToolCall;
import fan.summer.fengyu.ai.tools.AuditedToolCallback;
import fan.summer.fengyu.ai.tools.ChatToolApprovalGate;
import fan.summer.fengyu.ai.tools.ToolApprovalContext;
import fan.summer.fengyu.ai.tools.ToolEffect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S3+S4 through the tool surface: exec orchestrates REAL callbacks, nested calls that
 * need approval ask through the shared gate (code mode is never an approval bypass),
 * rejections become catchable results, a missing approval surface fails CLOSED, wait
 * resumes a yielded cell, and the off-state keeps exec inert.
 */
class CodeModeExecToolTest {

    /** A real audited COMMAND-effect callback the nested pipeline invokes. */
    static final class RecordingCallback implements AuditedToolCallback {
        final AtomicInteger calls = new AtomicInteger();
        volatile CountDownLatch blockOn;

        @Override public ToolEffect effect() { return ToolEffect.COMMAND; }

        @Override public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name("workspace_exec")
                    .description("run a command")
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"}}}")
                    .build();
        }

        @Override public ToolMetadata getToolMetadata() {
            return ToolMetadata.builder().returnDirect(false).build();
        }

        @Override public String call(String toolInput) {
            CountDownLatch latch = blockOn;
            if (latch != null) {
                try { latch.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            calls.incrementAndGet();
            return "{\"success\":true,\"output\":\"ran\"}";
        }
    }

    /** The tool with the surface forced on (the setting seam is registry-tested). */
    private static CodeModeExecTool activeTool(ToolCallback... nested) {
        List<ToolCallback> callbacks = List.of(nested);
        CodeModeExecTool tool = new CodeModeExecTool(() -> callbacks) {
            @Override protected boolean surfaceOn() { return true; }
        };
        return tool;
    }

    /** Installs an approval surface that auto-decides every request as scripted. */
    private static void approvalSurface(ChatToolApprovalGate.Decision decision,
            AtomicReference<AiToolCall> seen) {
        ChatToolApprovalGate gate = new ChatToolApprovalGate();
        AiStreamCallback stream = new AiStreamCallback() {
            @Override public void onToken(String fragment) { }
            @Override public void onToolApprovalRequired(String approvalId, AiToolCall toolCall,
                    Instant expiresAt) {
                if (seen != null) seen.set(toolCall);
                gate.resolve(decision, approvalId);
            }
        };
        ToolApprovalContext.set(gate, stream);
    }

    @AfterEach
    void clear() {
        ToolApprovalContext.clear();
    }

    @Test
    void offSurfaceKeepsExecInert() {
        String result = new CodeModeExecTool().exec("text('hi')");
        assertTrue(result.contains("not active"), result);
    }

    @Test
    void instantlyCompletedScriptsDeregisterTheirCell() {
        // A terminal first response (script finished inside the yield window) must leave
        // CELLS_BY_SESSION empty: a lingering handle would keep liveCellIds reporting a
        // dead cell forever, and a later wait() would poll a drained response queue.
        fan.summer.fengyu.ai.tools.ConversationContext.set(41L);
        try {
            CodeModeExecTool tool = activeTool();
            String result = tool.exec("text('done')");
            assertTrue(result.startsWith("Script completed"), result);
            assertTrue(CodeModeExecTool.liveCellIds(41L).isEmpty(),
                    "a finished cell must not stay live");
        } finally {
            fan.summer.fengyu.ai.tools.ConversationContext.clear();
        }
    }

    @Test
    void execOrchestratesARealNestedCallAfterApproval() {
        RecordingCallback nested = new RecordingCallback();
        AtomicReference<AiToolCall> seen = new AtomicReference<>();
        approvalSurface(ChatToolApprovalGate.Decision.approveOnce(), seen);

        String result = activeTool(nested).exec(
                "const r = await tools.workspace_exec({ command: 'make build' });\n"
                        + "text(r.success);");

        assertTrue(result.startsWith("Script completed"), result);
        assertTrue(result.contains("true"), result);
        assertEquals(1, nested.calls.get(), "the real callback ran exactly once");
        assertEquals("workspace_exec", seen.get().name(),
                "the approval card carried the nested call");
    }

    @Test
    void readonlyNestedCommandRunsApprovalFree() {
        // P0-1 regression: the RAW argument shape reaches the policy, so the readonly
        // whitelist sees the command and auto-runs it exactly like a model-issued call.
        RecordingCallback nested = new RecordingCallback();
        AtomicReference<AiToolCall> seen = new AtomicReference<>();
        approvalSurface(ChatToolApprovalGate.Decision.approveOnce(), seen);

        String result = activeTool(nested).exec(
                "const r = await tools.workspace_exec({ command: 'git status' });\n"
                        + "text(r.success);");
        assertTrue(result.startsWith("Script completed"), result);
        assertEquals(1, nested.calls.get());
        assertNull(seen.get(), "a readonly nested command never prompts");
    }

    @Test
    void approveForMeStillAsksForDangerousNestedCommands() {
        // P0-1 regression (the review's exploit): the envelope blinded the dangerous-
        // command patterns; with raw arguments the rm -rf regex must fire EVEN in
        // APPROVE_FOR_ME — and with no approval surface installed it fails closed.
        ToolApprovalContext.clear();
        fan.summer.fengyu.ai.tools.AiPermissionContext.set(
                fan.summer.fengyu.ai.tools.AiPermissionMode.APPROVE_FOR_ME);
        try {
            RecordingCallback nested = new RecordingCallback();
            String result = activeTool(nested).exec(
                    "try { await tools.workspace_exec({ command: 'rm -rf ~/Documents' });"
                            + " text('BYPASSED'); }"
                            + " catch (e) { text('blocked: ' + e.message); }");
            assertTrue(result.contains("blocked: nested call rejected"), result);
            assertEquals(0, nested.calls.get(),
                    "the dangerous nested command never executed");
        } finally {
            fan.summer.fengyu.ai.tools.AiPermissionContext.clear();
        }
    }

    @Test
    void guardDenyRejectsNestedCallsWithoutACard() {
        RecordingCallback nested = new RecordingCallback();
        ChatToolApprovalGate gate = new ChatToolApprovalGate(
                new fan.summer.fengyu.ai.tools.ToolGuardService(
                        new fan.summer.fengyu.ai.hooks.HookDispatcher(),
                        "{\"deny\":[\"Tool(workspace_exec)\"]}", null));
        AtomicReference<AiToolCall> seen = new AtomicReference<>();
        ToolApprovalContext.set(gate, new AiStreamCallback() {
            @Override public void onToken(String fragment) { }
            @Override public void onToolApprovalRequired(String id, AiToolCall call, Instant at) {
                seen.set(call);
            }
        });

        String result = activeTool(nested).exec(
                "try { await tools.workspace_exec({ command: 'npm install x' });"
                        + " text('NOT DENIED'); }"
                        + " catch (e) { text('denied: ' + e.message); }");
        assertTrue(result.contains("denied: nested call rejected"), result);
        assertEquals(0, nested.calls.get());
        assertNull(seen.get(), "a guard deny never reaches the approval card");
    }

    @Test
    void guardAllowRunsTheNestedCallApprovalFree() {
        RecordingCallback nested = new RecordingCallback();
        ChatToolApprovalGate gate = new ChatToolApprovalGate(
                new fan.summer.fengyu.ai.tools.ToolGuardService(
                        new fan.summer.fengyu.ai.hooks.HookDispatcher(),
                        "{\"allow\":[\"Tool(workspace_exec)\"]}", null));
        AtomicReference<AiToolCall> seen = new AtomicReference<>();
        ToolApprovalContext.set(gate, new AiStreamCallback() {
            @Override public void onToken(String fragment) { }
            @Override public void onToolApprovalRequired(String id, AiToolCall call, Instant at) {
                seen.set(call);
                gate.resolve(ChatToolApprovalGate.Decision.approveOnce(), id);
            }
        });

        String result = activeTool(nested).exec(
                "const r = await tools.workspace_exec({ command: 'make build' });"
                        + " text(r.success);");
        assertTrue(result.startsWith("Script completed"), result);
        assertEquals(1, nested.calls.get());
        assertNull(seen.get(), "an explicit allow suppresses the prompt");
    }

    @Test
    void rejectedNestedApprovalBecomesACatchableResult() {
        RecordingCallback nested = new RecordingCallback();
        approvalSurface(ChatToolApprovalGate.Decision.rejectOnce("stay readonly"), null);

        String result = activeTool(nested).exec("""
                try { await tools.workspace_exec({ command: 'rm -rf /' }); text('NOT CAUGHT'); }
                catch (e) { text('caught: ' + e.message); }
                """);
        assertTrue(result.startsWith("Script completed"), result);
        assertTrue(result.contains("caught: nested call rejected: stay readonly"), result);
        assertEquals(0, nested.calls.get(), "a rejected nested call never executes");
    }

    @Test
    void missingApprovalSurfaceFailsClosed() {
        ToolApprovalContext.clear();   // no gate, no stream — never a bypass
        RecordingCallback nested = new RecordingCallback();

        String result = activeTool(nested).exec(
                "try { await tools.workspace_exec({ command: 'x' }); text('BYPASSED'); }"
                        + " catch (e) { text('closed: ' + e.message); }");
        assertTrue(result.contains("closed: nested call rejected"), result);
        assertEquals(0, nested.calls.get());
    }

    @Test
    void execCannotCallItself() {
        String result = activeTool().exec(
                "try { await tools.exec('text(1)'); text('SELF CALLED'); }"
                        + " catch (e) { text('refused: ' + e.message); }");
        assertTrue(result.contains("refused: No tool named 'exec'"), result);
    }

    @Test
    void yieldedCellResumesThroughWaitAndTerminates() throws Exception {
        RecordingCallback nested = new RecordingCallback();
        CountDownLatch release = new CountDownLatch(1);
        nested.blockOn = release;
        approvalSurface(ChatToolApprovalGate.Decision.approveOnce(), null);

        CodeModeExecTool tool = activeTool(nested);
        String first = tool.exec("// @exec: {\"yield_time_ms\": 200}\n"
                + "const r = await tools.workspace_exec({ command: 'build' });\n"
                + "text('after'); ");
        assertTrue(first.startsWith("Script running with cell ID "), first);

        release.countDown();
        // Poll wait until the terminal result lands (the nested call may take a moment).
        String cellId = first.replace("Script running with cell ID ", "").split("\n")[0].trim();
        String resumed = null;
        for (int i = 0; i < 30 && resumed == null; i++) {
            String next = tool.wait(cellId, 300, null);
            if (next != null && (next.startsWith("Script completed")
                    || next.startsWith("Script terminated")
                    || next.startsWith("Script failed"))) {
                resumed = next;
            }
        }
        assertTrue(resumed != null && resumed.startsWith("Script completed"), String.valueOf(resumed));
        assertTrue(resumed.contains("after"), resumed);
    }

    // ── pure formatting + description assembly ─────────────────────────────────────────

    @Test
    void formatTemplatesMatchTheCodexShapes() {
        assertEquals("Script completed\nhello\n", CodeModeExecTool.format(
                new CodeModeProtocol.Result("c1",
                        List.of(new CodeModeProtocol.Text("hello")), null)));
        assertTrue(CodeModeExecTool.format(new CodeModeProtocol.Result("c1", List.of(), "boom"))
                .startsWith("Script failed\nScript error:\nboom"));
        assertEquals("Script terminated", CodeModeExecTool.format(
                new CodeModeProtocol.Terminated("c1")));
        assertTrue(CodeModeExecTool.format(new CodeModeProtocol.Yielded("c1",
                List.of(new CodeModeProtocol.Text("partial")))).startsWith(
                "Script running with cell ID c1"));
    }

    @Test
    void formatTruncationNeverSplitsASurrogatePair() throws Exception {
        // "😀" is two UTF-16 chars (a surrogate pair). The budget applies to the whole
        // formatted string — "Script completed\n" (17 chars) + content — so place the
        // pair astride the cut and assert it is dropped or kept WHOLE, never split.
        String emoji = "\uD83D\uDE00";
        String content = "x".repeat(9) + emoji + "y".repeat(10);
        CodeModeProtocol.Result result = new CodeModeProtocol.Result("c1",
                List.of(new CodeModeProtocol.Text(content)), null);

        // Cut inside the pair: back off to before the high surrogate (9 x's, pair dropped).
        String split = CodeModeExecTool.format(result, 17 + 10);
        assertTrue(split.endsWith("\n…[output truncated]"), split);
        String kept = split.substring("Script completed\n".length(),
                split.indexOf('\n', "Script completed\n".length()));
        assertEquals("x".repeat(9), kept);

        // Cut just past the pair: the intact emoji survives.
        String whole = CodeModeExecTool.format(result, 17 + 11);
        String keptPair = whole.substring("Script completed\n".length(),
                whole.indexOf('\n', "Script completed\n".length()));
        assertEquals("x".repeat(9) + emoji, keptPair);
    }

    @Test
    void descriptionExcludesExecAndWaitThemselves() {
        String description = CodeModeExecTool.descriptionFor(List.of(
                callbackNamed("exec"), callbackNamed("wait"), callbackNamed("workspace_exec")));
        assertFalse(description.contains("  exec("));
        assertFalse(description.contains("  wait("));
        assertTrue(description.contains("workspace_exec("));
    }

    private static ToolCallback callbackNamed(String name) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name(name)
                        .description("d-" + name).inputSchema("{}").build();
            }
            @Override public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(false).build();
            }
            @Override public String call(String toolInput) { return "ok"; }
        };
    }
}
