package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiStreamCallback;
import fan.summer.fengyu.ai.ChatBackend;
import fan.summer.fengyu.ai.AiToolCall;
import fan.summer.fengyu.ai.AiToolResult;
import fan.summer.fengyu.ai.tools.ApprovalRequiredToolCallback;
import fan.summer.fengyu.ai.tools.AiPermissionContext;
import fan.summer.fengyu.ai.tools.AiPermissionMode;
import fan.summer.fengyu.ai.tools.ChatToolApprovalGate;
import fan.summer.fengyu.ai.tools.ConversationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 4.1.0 per-turn drivers, pinned at the REAL execution layer (the controller-level
 * concurrency test uses a fake backend): two turns must run in parallel on one backend
 * instance, and cancelling one turn's {@link ChatBackend.GenerationHandle} must release
 * exactly that turn's pending approval card — the driver stamps the TurnScope group and
 * passes it to {@code cancelPendingFor}; a wrong (or null) group would fall back to the
 * GLOBAL sweep and kill the other conversation's card.
 */
class ToolLoopDriverConcurrencyTest {

    @BeforeAll
    static void initConfigInstance() throws Exception {
        // Same INSTANCE seeding trick as ChatCancelMidStreamTest: the driver reads
        // max-tool-rounds/window statics on bare unit threads.
        fan.summer.fengyu.database.repository.AppSettingRepository repo =
                org.mockito.Mockito.mock(fan.summer.fengyu.database.repository.AppSettingRepository.class);
        fan.summer.fengyu.security.SecurityContext ctx =
                org.mockito.Mockito.mock(fan.summer.fengyu.security.SecurityContext.class);
        org.mockito.Mockito.when(repo.findByUserIdAndSettingKey(org.mockito.Mockito.anyLong(),
                        org.mockito.Mockito.anyString()))
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

    /** Both turns' first onToken/error signal, plus the captured error message. */
    private static final class TurnProbe {
        final CountDownLatch terminal = new CountDownLatch(1);
        final AtomicReference<String> error = new AtomicReference<>();
        final CountDownLatch token = new CountDownLatch(1);
        final CountDownLatch approval = new CountDownLatch(1);

        AiStreamCallback callback() {
            return new AiStreamCallback() {
                @Override public void onToken(String fragment) { token.countDown(); }
                @Override public void onToolCall(AiToolCall tc) { }
                @Override public void onToolResult(String id, AiToolResult r) { }
                @Override public void onToolApprovalRequired(
                        String id, AiToolCall call, java.time.Instant expiresAt) {
                    approval.countDown();
                }
                @Override public void onComplete(String s, int t, double r) { }
                @Override public void onError(Throwable t) {
                    error.set(String.valueOf(t.getMessage()));
                    terminal.countDown();
                }
            };
        }
    }

    @Test
    void twoTurnsRunInParallelOnOneBackendAndCancelIsolates() throws Exception {
        SpringAiCloudBackend backend =
                new SpringAiCloudBackend(new ChatCancelMidStreamTest.NeverCompletingModel());
        TurnProbe turn1 = new TurnProbe();
        TurnProbe turn2 = new TurnProbe();

        ConversationContext.set(1L);
        ChatBackend.GenerationHandle handle1 =
                backend.chat(history("one"), 0.7f, 0.9f, 256, turn1.callback());
        ConversationContext.set(2L);
        ChatBackend.GenerationHandle handle2 =
                backend.chat(history("two"), 0.7f, 0.9f, 256, turn2.callback());

        assertTrue(turn1.token.await(5, TimeUnit.SECONDS), "turn 1 streams");
        assertTrue(turn2.token.await(5, TimeUnit.SECONDS),
                "turn 2 streams in parallel — a shared-driver regression would throw "
                        + "'Generation already in progress' here");
        assertTrue(backend.isGenerating());

        handle1.cancel();
        assertTrue(turn1.terminal.await(5, TimeUnit.SECONDS), "turn 1 ends cancelled");
        assertTrue(turn1.error.get().contains("cancelled"));
        assertFalse(turn2.terminal.await(300, TimeUnit.MILLISECONDS),
                "cancelling turn 1 must not terminate turn 2");

        handle2.cancel();
        assertTrue(turn2.terminal.await(5, TimeUnit.SECONDS));
        // onError fires in the worker's catch, the liveDrivers deregistration in its
        // finally — a hair LATER. Poll instead of asserting immediately.
        long idleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (backend.isGenerating() && System.nanoTime() < idleDeadline) Thread.sleep(10);
        assertFalse(backend.isGenerating(), "the backend is idle once both turns end");
    }

    /** A model whose single round requests the approval-gated {@code echo} tool. */
    private static final class ToolRequestingModel implements org.springframework.ai.chat.model.ChatModel {
        @Override public org.springframework.ai.chat.model.ChatResponse call(
                org.springframework.ai.chat.prompt.Prompt prompt) {
            throw new UnsupportedOperationException();
        }

        @Override public reactor.core.publisher.Flux<ChatResponse> stream(
                org.springframework.ai.chat.prompt.Prompt prompt) {
            AssistantMessage am = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "call_1", "function", "echo", "{\"text\":\"hi\"}")))
                    .build();
            return reactor.core.publisher.Flux.just(new ChatResponse(List.of(new Generation(am))));
        }
    }

    @Test
    void cancelHandleReleasesOnlyItsConversationsApprovalCards() throws Exception {
        SpringAiCloudBackend backend = new SpringAiCloudBackend(new ToolRequestingModel());
        ChatToolApprovalGate gate = new ChatToolApprovalGate();
        backend.setToolCallbacks(List.of(approvalGatedEcho()));
        backend.setToolApprovalGate(gate);
        AiPermissionContext.set(AiPermissionMode.ASK_FOR_APPROVAL);
        TurnProbe turn1 = new TurnProbe();
        TurnProbe turn2 = new TurnProbe();

        ConversationContext.set(1L);
        ChatBackend.GenerationHandle handle1 =
                backend.chat(history("one"), 0.7f, 0.9f, 256, turn1.callback());
        ConversationContext.set(2L);
        ChatBackend.GenerationHandle handle2 =
                backend.chat(history("two"), 0.7f, 0.9f, 256, turn2.callback());

        assertTrue(turn1.approval.await(5, TimeUnit.SECONDS), "turn 1 shows its approval card");
        assertTrue(turn2.approval.await(5, TimeUnit.SECONDS), "turn 2 shows its approval card");

        handle1.cancel();
        assertTrue(turn1.terminal.await(5, TimeUnit.SECONDS),
                "the cancelled turn's gate entry is released (right group, not lost)");
        // The gate release and the worker interrupt race for WHO wakes the await — both
        // are legitimate cancel terminals ("cancelled" via gate, "interrupted" via
        // interrupt); what matters is that the turn ENDED and turn 2 did not.
        assertTrue(turn1.error.get().contains("cancelled")
                        || turn1.error.get().contains("interrupted"),
                "turn 1 ended as a cancellation, got: " + turn1.error.get());
        assertFalse(turn2.terminal.await(300, TimeUnit.MILLISECONDS),
                "conversation 2's card must survive conversation 1's cancellation — a null "
                        + "or wrong group would fall back to the gate's GLOBAL sweep");

        handle2.cancel();
        assertTrue(turn2.terminal.await(5, TimeUnit.SECONDS));
    }

    private static List<AiChatMessage> history(String prompt) {
        return new ArrayList<>(List.of(AiChatMessage.user(prompt)));
    }

    private static ToolCallback approvalGatedEcho() {
        ToolDefinition definition = DefaultToolDefinition.builder()
                .name("echo")
                .description("echoes the provided text back")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}")
                .build();
        return new ApprovalRequiredToolCallback() {
            private final AtomicInteger invocations = new AtomicInteger();
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public String call(String input) {
                invocations.incrementAndGet();
                return "echo:" + input;
            }
        };
    }
}
