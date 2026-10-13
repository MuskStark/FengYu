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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Driver-level pins for the unified {@code @} mention surface: a
 * {@code [@label](skill://id)} link in the last user message loads the skill exactly like
 * the {@code /skill-id} grammar does (synthetic activity pair on the tool SSE surface,
 * history untouched), a message combining both surfaces injects each skill once, and the
 * no-tools (machine) entry point never parses links. Same never-completing-model seam as
 * {@link ToolLoopDriverExplicitSkillTest}.
 */
class ToolLoopDriverExplicitMentionTest {

    @BeforeAll
    static void initConfigInstance() throws Exception {
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

    private static final Skill REVIEW = new Skill(
            "code-review", "Code review", "reviews diffs", "## Review\nBe kind.", Skill.Source.BUILTIN);
    private static final Skill DOCS = new Skill(
            "fengyu-plugin-dev", "Plugin dev", "guides authors",
            "## Dev\nBuild it.", Skill.Source.BUILTIN);

    private static SkillRegistry registry() {
        SkillRegistry registry = Mockito.mock(SkillRegistry.class);
        Mockito.when(registry.find("code-review")).thenReturn(Optional.of(REVIEW));
        Mockito.when(registry.isEnabled(REVIEW)).thenReturn(true);
        Mockito.when(registry.find("fengyu-plugin-dev")).thenReturn(Optional.of(DOCS));
        Mockito.when(registry.isEnabled(DOCS)).thenReturn(true);
        Mockito.when(registry.enabled()).thenReturn(List.of(REVIEW, DOCS));
        return registry;
    }

    /** Captures every synthetic skill pair; terminal lets the test await cancel cleanup. */
    private static final class MentionProbe {
        final CountDownLatch call = new CountDownLatch(1);
        final CountDownLatch terminal = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        final List<String> ids = java.util.Collections.synchronizedList(new ArrayList<>());

        AiStreamCallback callback() {
            return new AiStreamCallback() {
                @Override public void onToken(String fragment) { }
                @Override public void onToolCall(AiToolCall toolCall) {
                    if ("skill".equals(toolCall.name())) {
                        ids.add(String.valueOf(toolCall.arguments().get("id")));
                        calls.incrementAndGet();
                        call.countDown();
                    }
                }
                @Override public void onToolResult(String toolCallId, AiToolResult res) { }
                @Override public void onError(Throwable t) { terminal.countDown(); }
                @Override public void onComplete(String s, int t, double r) { terminal.countDown(); }
            };
        }
    }

    @Test
    void mentionLinkLoadsTheSkillLikeTheSlashGrammar() throws Exception {
        SpringAiCloudBackend backend =
                new SpringAiCloudBackend(new ChatCancelMidStreamTest.NeverCompletingModel());
        backend.setSkillRegistry(registry());
        AiPermissionContext.set(AiPermissionMode.FULL_ACCESS);
        ConversationContext.set(1L);
        MentionProbe probe = new MentionProbe();
        List<AiChatMessage> history = new ArrayList<>(List.of(AiChatMessage.user(
                "review this please\n\n[@Code review](skill://code-review)")));

        ChatBackend.GenerationHandle handle = backend.chat(history, 0.7f, 0.9f, 256, probe.callback());

        assertTrue(probe.call.await(5, TimeUnit.SECONDS), "the mention link loads the skill");
        assertTrue(probe.ids.contains("code-review"), String.valueOf(probe.ids));
        // Stream-only invariant: the persisted conversation replays exactly the user's text.
        assertEquals(1, history.size());
        assertEquals("review this please\n\n[@Code review](skill://code-review)",
                history.getFirst().content());

        handle.cancel();
        assertTrue(probe.terminal.await(5, TimeUnit.SECONDS), "turn ends cancelled");
    }

    @Test
    void commandAndMentionNamingTheSameSkillInjectsOnce() throws Exception {
        SpringAiCloudBackend backend =
                new SpringAiCloudBackend(new ChatCancelMidStreamTest.NeverCompletingModel());
        backend.setSkillRegistry(registry());
        AiPermissionContext.set(AiPermissionMode.FULL_ACCESS);
        ConversationContext.set(2L);
        MentionProbe probe = new MentionProbe();
        List<AiChatMessage> history = new ArrayList<>(List.of(AiChatMessage.user(
                "/code-review [@Code review](skill://code-review) and"
                        + " [@Plugin dev](skill://fengyu-plugin-dev)")));

        ChatBackend.GenerationHandle handle = backend.chat(history, 0.7f, 0.9f, 256, probe.callback());

        assertTrue(probe.call.await(5, TimeUnit.SECONDS));
        // One synthetic pair per DISTINCT skill: the / command's skill and one mention skill;
        // the link re-naming the / command's skill must not double-load it. The events may
        // land within the same await window, so give the pair a moment to settle.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        int seen;
        do {
            seen = probe.calls.get();
            if (seen >= 2) break;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        assertEquals(2, probe.calls.get(), "two distinct skills, no duplicate for the overlap");
        assertTrue(probe.ids.contains("fengyu-plugin-dev"), String.valueOf(probe.ids));
        assertEquals(1, probe.ids.stream().filter("code-review"::equals).count());

        handle.cancel();
        assertTrue(probe.terminal.await(5, TimeUnit.SECONDS), "turn ends cancelled");
    }

    @Test
    void machineTurnsWithoutToolsNeverParseMentionLinks() throws Exception {
        SpringAiCloudBackend backend =
                new SpringAiCloudBackend(new ChatCancelMidStreamTest.NeverCompletingModel());
        backend.setSkillRegistry(registry());
        AiPermissionContext.set(AiPermissionMode.FULL_ACCESS);
        ConversationContext.set(3L);
        MentionProbe probe = new MentionProbe();

        ChatBackend.GenerationHandle handle = backend.chatWithoutTools(
                new ArrayList<>(List.of(AiChatMessage.user(
                        "[@Code review](skill://code-review) must not load"))),
                probe.callback());

        assertFalse(probe.call.await(300, TimeUnit.MILLISECONDS),
                "chatWithoutTools is the planner/machine surface — no mention semantics");
        handle.cancel();
        assertTrue(probe.terminal.await(5, TimeUnit.SECONDS), "turn ends cancelled");
    }
}
