package fan.summer.fengyu.ai.codemode;

import fan.summer.fengyu.ai.AiStreamCallback;
import fan.summer.fengyu.ai.AiToolCall;
import fan.summer.fengyu.ai.tools.ChatToolApprovalGate;
import fan.summer.fengyu.ai.tools.ToolApprovalContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Code-mode cell lifetime governance: cells record lastActivity, the exec/wait entry
 * points sweep cells past their idle or absolute-lifetime budgets (thresholds and clock
 * are injectable — a fake clock drives the sweep deterministically), and the
 * conversation-end hook ({@code closeSessionFor}) evicts the session and terminates its
 * cells idempotently. The turn-cancel cascade is covered by
 * {@code CodeModeTurnInterruptTest} and deliberately untouched here.
 */
class CodeModeCellLifetimeTest {

    private static final Long CONVERSATION = 71L;

    private final AtomicLong clock = new AtomicLong(1_000_000);
    /** Blocks the yielded cell's nested call until the test releases it. */
    private final CountDownLatch release = new CountDownLatch(1);

    /** A real audited COMMAND-effect callback that blocks, keeping the cell alive. */
    static final class BlockingCallback implements
            fan.summer.fengyu.ai.tools.AuditedToolCallback {
        final CountDownLatch blockOn;

        BlockingCallback(CountDownLatch blockOn) {
            this.blockOn = blockOn;
        }

        @Override public fan.summer.fengyu.ai.tools.ToolEffect effect() {
            return fan.summer.fengyu.ai.tools.ToolEffect.COMMAND;
        }

        @Override public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name("workspace_exec")
                    .description("run a command")
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"command\":"
                            + "{\"type\":\"string\"}}}")
                    .build();
        }

        @Override public ToolMetadata getToolMetadata() {
            return ToolMetadata.builder().returnDirect(false).build();
        }

        @Override public String call(String toolInput) {
            try {
                blockOn.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "{\"success\":true,\"output\":\"ran\"}";
        }
    }

    @BeforeEach
    void armFakeClockAndSurface() {
        CodeModeExecTool.CLOCK = () -> clock.get();
        CodeModeExecTool.cellIdleTimeoutMs = CodeModeExecTool.DEFAULT_CELL_IDLE_TIMEOUT_MS;
        CodeModeExecTool.cellMaxLifetimeMs = CodeModeExecTool.DEFAULT_CELL_MAX_LIFETIME_MS;
        fan.summer.fengyu.ai.tools.ConversationContext.set(CONVERSATION);
        // Auto-approving surface: the yielded cell's nested call then blocks inside the
        // real callback (BlockingCallback), which is what keeps the cell genuinely live.
        ChatToolApprovalGate gate = new ChatToolApprovalGate();
        ToolApprovalContext.set(gate, new AiStreamCallback() {
            @Override public void onToken(String fragment) {}
            @Override public void onToolApprovalRequired(String approvalId, AiToolCall toolCall,
                    Instant expiresAt) {
                gate.resolve(ChatToolApprovalGate.Decision.approveOnce(), approvalId);
            }
        });
    }

    @AfterEach
    void restoreSeams() {
        release.countDown();
        CodeModeExecTool.closeSessionFor(CONVERSATION);
        fan.summer.fengyu.ai.tools.ConversationContext.clear();
        ToolApprovalContext.clear();
        CodeModeExecTool.CLOCK = System::currentTimeMillis;
        CodeModeExecTool.cellIdleTimeoutMs = CodeModeExecTool.DEFAULT_CELL_IDLE_TIMEOUT_MS;
        CodeModeExecTool.cellMaxLifetimeMs = CodeModeExecTool.DEFAULT_CELL_MAX_LIFETIME_MS;
    }

    /** A tool whose exec yields a live cell blocked inside a nested call. */
    private CodeModeExecTool toolWithBlockedCall() {
        List<ToolCallback> callbacks = List.of(new BlockingCallback(release));
        return new CodeModeExecTool(() -> callbacks) {
            @Override protected boolean surfaceOn() { return true; }
        };
    }

    /** Starts one exec that yields (the cell stays live), returning its cell id. */
    private String yieldCell(CodeModeExecTool tool) {
        String first = tool.exec("// @exec: {\"yield_time_ms\": 200}\n"
                + "const r = await tools.workspace_exec({ command: 'build' });\n"
                + "text('after'); ");
        assertTrue(first.startsWith("Script running with cell ID "), first);
        return first.replace("Script running with cell ID ", "").split("\n")[0].trim();
    }

    @Test
    void idleCellsAreSweptAtTheEntryPoints() {
        CodeModeExecTool tool = toolWithBlockedCall();
        String cellId = yieldCell(tool);
        assertTrue(CodeModeExecTool.liveCellIds(CONVERSATION).contains(cellId));

        // Advance the fake clock past the idle budget, then re-enter through exec: the
        // stale cell is terminated and deregistered, not left holding its context.
        clock.addAndGet(CodeModeExecTool.cellIdleTimeoutMs + 1);
        assertTrue(tool.exec("text('a fresh cell')").startsWith("Script completed"));

        assertFalse(CodeModeExecTool.liveCellIds(CONVERSATION).contains(cellId),
                "the idle cell was swept");
        assertTrue(tool.wait(cellId, 0, null).startsWith("Unknown cell ID"),
                "a swept cell is no longer resumable");
    }

    /**
     * The scheduler-driven sweep (review P2): the entry-point sweep only runs when some
     * later exec/wait is issued, so a cell nobody ever polled again must also die at its
     * idle budget — without any re-entry into the tool.
     */
    @Test
    void theSchedulerSweepTerminatesCellsNobodyPolled() {
        CodeModeExecTool tool = toolWithBlockedCall();
        String cellId = yieldCell(tool);
        assertTrue(CodeModeExecTool.liveCellIds(CONVERSATION).contains(cellId));

        clock.addAndGet(CodeModeExecTool.cellIdleTimeoutMs + 1);
        tool.sweepStaleCellsOnSchedule();   // no exec/wait re-entry involved

        assertFalse(CodeModeExecTool.liveCellIds(CONVERSATION).contains(cellId),
                "the scheduler alone must retire an abandoned cell");
        assertTrue(tool.wait(cellId, 0, null).startsWith("Unknown cell ID"),
                "a scheduler-swept cell is no longer resumable");
    }

    @Test
    void recentlyActiveCellsSurviveTheSweep() {
        CodeModeExecTool tool = toolWithBlockedCall();
        String cellId = yieldCell(tool);

        // Idle, but refreshed by a wait() just before the sweep: still live.
        clock.addAndGet(CodeModeExecTool.cellIdleTimeoutMs / 2);
        String stillRunning = tool.wait(cellId, 0, null);
        assertTrue(stillRunning.startsWith("Script running"), stillRunning);
        tool.exec("text('sweep trigger')");
        assertTrue(CodeModeExecTool.liveCellIds(CONVERSATION).contains(cellId),
                "a refreshed cell must not be swept");
    }

    @Test
    void absoluteLifetimeCeilingTerminatesEvenConstantlyRefreshedCells() {
        // A chatty zombie that keeps refreshing lastActivity must still die at the
        // absolute ceiling — shrink the ceiling, keep the idle budget huge.
        CodeModeExecTool.cellMaxLifetimeMs = 60_000;
        CodeModeExecTool.cellIdleTimeoutMs = 10_000_000;
        CodeModeExecTool tool = toolWithBlockedCall();
        String cellId = yieldCell(tool);

        // Keep polling (refreshing activity) while the lifetime budget runs out.
        for (int i = 0; i < 3; i++) {
            clock.addAndGet(20_000);
            tool.wait(cellId, 0, null);
        }
        clock.addAndGet(10_000);   // total 70s > 60s ceiling, still far from idle
        tool.exec("text('sweep trigger')");

        assertFalse(CodeModeExecTool.liveCellIds(CONVERSATION).contains(cellId),
                "the lifetime ceiling is absolute");
    }

    @Test
    void conversationEndHookEvictsTheSessionAndItsCells() {
        CodeModeExecTool tool = toolWithBlockedCall();
        String cellId = yieldCell(tool);
        assertTrue(CodeModeExecTool.sessionLive(CONVERSATION));

        int terminated = CodeModeExecTool.closeSessionFor(CONVERSATION);

        assertEquals(1, terminated, "the one live cell was terminated");
        assertTrue(CodeModeExecTool.liveCellIds(CONVERSATION).isEmpty());
        assertFalse(CodeModeExecTool.sessionLive(CONVERSATION), "the session is dropped");
        // Idempotent and thread-safe: a second eviction is a no-op.
        assertEquals(0, CodeModeExecTool.closeSessionFor(CONVERSATION));
    }

    @Test
    void waitYieldWindowIsClamped() {
        // 0 keeps its immediate meaning; in-range values pass through; out-of-range
        // values are pulled into the 1s–60s window (the same bound exec applies).
        assertEquals(0, CodeModeExecTool.clampYieldWindow(0));
        assertEquals(0, CodeModeExecTool.clampYieldWindow(-5));
        assertEquals(1_000, CodeModeExecTool.clampYieldWindow(1));
        assertEquals(1_000, CodeModeExecTool.clampYieldWindow(999));
        assertEquals(10_000, CodeModeExecTool.clampYieldWindow(10_000));
        assertEquals(60_000, CodeModeExecTool.clampYieldWindow(120_000));
        assertEquals(60_000, CodeModeExecTool.clampYieldWindow(Integer.MAX_VALUE));
    }
}
