package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiStreamCallback;
import fan.summer.fengyu.ai.ChatBackend;
import fan.summer.fengyu.ai.AiToolCall;
import fan.summer.fengyu.ai.AiToolResult;
import fan.summer.fengyu.ai.skill.Skill;
import fan.summer.fengyu.ai.skill.SkillRegistry;
import fan.summer.fengyu.ai.tools.AiPermissionContext;
import fan.summer.fengyu.ai.tools.AiPermissionMode;
import fan.summer.fengyu.ai.tools.ConversationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Driver-level pins for explicit skill invocation (`/skill-id args`): the turn emits the
 * synthetic skill activity pair on the tool SSE surface BEFORE the first model round,
 * never appends anything to the caller's history, and skips the whole path on the
 * no-tools (machine) entry point. Runs against the real {@code ToolLoopDriver} through
 * {@code SpringAiCloudBackend} with a never-completing model, so the assertions observe
 * exactly the pre-first-round seam the events live at.
 */
class ToolLoopDriverExplicitSkillTest {

    @BeforeAll
    static void initConfigInstance() throws Exception {
        // Same INSTANCE seeding trick as ToolLoopDriverConcurrencyTest: the driver reads
        // max-tool-rounds/window statics on bare unit threads.
        fan.summer.fengyu.database.repository.AppSettingRepository repo =
                Mockito.mock(fan.summer.fengyu.database.repository.AppSettingRepository.class);
        fan.summer.fengyu.security.SecurityContext ctx =
                Mockito.mock(fan.summer.fengyu.security.SecurityContext.class);
        Mockito.when(repo.findByUserIdAndSettingKey(Mockito.anyLong(), Mockito.anyString()))
                .thenThrow(new RuntimeException("no db in unit test"));
        java.lang.reflect.Field f = fan.summer.fengyu.ai.AiConfigService.class.getDeclaredField("INSTANCE");
        f.setAccessible(true);
        f.set(null, new fan.summer.fengyu.ai.AiConfigService(repo, ctx));
    }

    @AfterEach
    void clearContexts() {
        ConversationContext.clear();
        AiPermissionContext.clear();
    }

    private static final Skill SKILL = new Skill(
            "fengyu-plugin-dev", "Plugin dev", "guides authors",
            "## Guidance\nBuild it.", Skill.Source.BUILTIN);

    private static SkillRegistry registry() {
        SkillRegistry registry = Mockito.mock(SkillRegistry.class);
        Mockito.when(registry.find("fengyu-plugin-dev")).thenReturn(Optional.of(SKILL));
        Mockito.when(registry.isEnabled(SKILL)).thenReturn(true);
        Mockito.when(registry.enabled()).thenReturn(List.of(SKILL));
        return registry;
    }

    /** Captures the synthetic pair; terminal lets the test await cancel cleanup. */
    private static final class SkillProbe {
        final CountDownLatch call = new CountDownLatch(1);
        final CountDownLatch result = new CountDownLatch(1);
        final CountDownLatch terminal = new CountDownLatch(1);
        final AtomicReference<AiToolCall> seenCall = new AtomicReference<>();
        final AtomicReference<AiToolResult> seenResult = new AtomicReference<>();

        AiStreamCallback callback() {
            return new AiStreamCallback() {
                @Override public void onToken(String fragment) { }
                @Override public void onToolCall(AiToolCall toolCall) {
                    if ("skill".equals(toolCall.name())) {
                        seenCall.set(toolCall);
                        call.countDown();
                    }
                }
                @Override public void onToolResult(String toolCallId, AiToolResult res) {
                    if (seenCall.get() != null && seenCall.get().id().equals(toolCallId)) {
                        seenResult.set(res);
                        result.countDown();
                    }
                }
                @Override public void onError(Throwable t) { terminal.countDown(); }
                @Override public void onComplete(String s, int t, double r) { terminal.countDown(); }
            };
        }
    }

    @Test
    void explicitInvocationEmitsSyntheticPairAndKeepsHistoryClean() throws Exception {
        SpringAiCloudBackend backend =
                new SpringAiCloudBackend(new ChatCancelMidStreamTest.NeverCompletingModel());
        backend.setSkillRegistry(registry());
        AiPermissionContext.set(AiPermissionMode.FULL_ACCESS);
        ConversationContext.set(1L);
        SkillProbe probe = new SkillProbe();
        List<AiChatMessage> history = new ArrayList<>(
                List.of(AiChatMessage.user("/fengyu-plugin-dev scaffold a worker")));

        ChatBackend.GenerationHandle handle = backend.chat(history, 0.7f, 0.9f, 256, probe.callback());

        assertTrue(probe.call.await(5, TimeUnit.SECONDS), "the synthetic skill call fires");
        assertEquals("fengyu-plugin-dev", probe.seenCall.get().arguments().get("id"));
        assertTrue(probe.result.await(5, TimeUnit.SECONDS), "the synthetic result completes the card");
        assertTrue(probe.seenResult.get().success());
        // Stream-only invariant: the synthetic activity must never become history — the
        // persisted conversation replays exactly the user's original message.
        assertEquals(1, history.size());
        assertEquals("/fengyu-plugin-dev scaffold a worker", history.getFirst().content());

        handle.cancel();
        assertTrue(probe.terminal.await(5, TimeUnit.SECONDS), "turn ends cancelled");
    }

    @Test
    void machineTurnsWithoutToolsNeverParseTheCommand() throws Exception {
        SpringAiCloudBackend backend =
                new SpringAiCloudBackend(new ChatCancelMidStreamTest.NeverCompletingModel());
        backend.setSkillRegistry(registry());
        AiPermissionContext.set(AiPermissionMode.FULL_ACCESS);
        ConversationContext.set(2L);
        SkillProbe probe = new SkillProbe();

        ChatBackend.GenerationHandle handle = backend.chatWithoutTools(
                new ArrayList<>(List.of(AiChatMessage.user("/fengyu-plugin-dev should not load"))),
                probe.callback());

        assertFalse(probe.call.await(300, TimeUnit.MILLISECONDS),
                "chatWithoutTools is the planner/machine surface — no command semantics");
        handle.cancel();
        assertTrue(probe.terminal.await(5, TimeUnit.SECONDS), "turn ends cancelled");
    }

    /** Pins the budget ladder: bytes/4 estimator alignment, tiny-window shrink, caps. */
    @Test
    void budgetLadderIsPinned() {
        assertEquals(0, ToolLoopDriver.explicitSkillBudgetBytes(0),
                "window 0 = compaction-off accepts full sends");
        assertEquals(0, ToolLoopDriver.explicitSkillBudgetBytes(-1));
        assertEquals(1024, ToolLoopDriver.explicitSkillBudgetBytes(1024),
                "tiny windows floor at 1 KB");
        assertEquals(1024, ToolLoopDriver.explicitSkillBudgetBytes(2048), "2048/8*4 = 1024");
        assertEquals(2044, ToolLoopDriver.explicitSkillBudgetBytes(4095), "last tiny window: 4095/8*4 (int div)");
        assertEquals(4096, ToolLoopDriver.explicitSkillBudgetBytes(4096), "cutoff goes to quarter: 4096/4*4");
        assertEquals(6144, ToolLoopDriver.explicitSkillBudgetBytes(6144), "quarter of the window in bytes");
        assertEquals(32768, ToolLoopDriver.explicitSkillBudgetBytes(32768), "8k-token ceiling * 4 bytes");
        assertEquals(32768, ToolLoopDriver.explicitSkillBudgetBytes(131072), "ceiling holds at 128k");
    }
}
