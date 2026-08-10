package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiConfigService;
import fan.summer.fengyu.ai.AiStreamCallback;
import fan.summer.fengyu.ai.AiToolCall;
import fan.summer.fengyu.ai.AiToolResult;
import fan.summer.fengyu.security.SecurityContext;
import fan.summer.fengyu.database.repository.AppSettingRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the fix for "stop AI does not stop an in-flight tool call": {@code cancelGeneration()}
 * must interrupt the worker virtual thread blocked inside a tool (mirroring the {@code AgentRun}
 * pattern), not only dispose the LLM stream. Before the fix a blocking tool kept running after
 * the user clicked stop, and the loop re-prompted the model with the swallowed failure envelope.
 *
 * <p>The test registers a tool that blocks on a {@link CountDownLatch} (simulating
 * {@code BrowserBridgeClient.invoke}'s HTTP send), starts a chat, then cancels and asserts:
 * <ul>
 *   <li>{@code onError} fires promptly (the worker did NOT wait for the tool's own timeout),</li>
 *   <li>the blocked tool call observes its interrupt (the latch-await threw),</li>
 *   <li>{@code isGenerating()} clears, so a subsequent chat is not wedged.</li>
 * </ul>
 */
class ChatCancelInterruptsToolTest {

    /**
     * The backend's {@code startChat} reads {@code AiConfigService.getAiMaxToolRounds()} which
     * hits the static {@code INSTANCE}. Spring never initialises it in a plain unit test, so we
     * install a stub whose {@code readSetting} returns the default (repo lookup throws → default
     * path), exactly like the existing ChatClientToolLoopTest relies on by test-order luck.
     */
    @BeforeAll
    static void initConfigInstance() throws Exception {
        AppSettingRepository repo = Mockito.mock(AppSettingRepository.class);
        SecurityContext ctx = Mockito.mock(SecurityContext.class);
        Mockito.when(repo.findByUserIdAndSettingKey(Mockito.anyLong(), Mockito.anyString()))
                .thenThrow(new RuntimeException("no db in unit test"));
        AiConfigService stub = new AiConfigService(repo, ctx);
        java.lang.reflect.Field f = AiConfigService.class.getDeclaredField("INSTANCE");
        f.setAccessible(true);
        f.set(null, stub);
    }

    /** Model that requests a {@code blocking} tool on the first stream (never reaches a second). */
    static final class ToolRequestingModel implements ChatModel {
        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            AssistantMessage am = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "call_1", "function", "blocking", "{}")))
                    .build();
            return Flux.just(new ChatResponse(List.of(new Generation(am))));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * A tool that blocks until released, recording whether it was interrupted. Mirrors a
     * long-running {@code browser_*} call (e.g. {@code browser_navigate}'s 60s HTTP send).
     */
    static final class BlockingToolCallback implements ToolCallback {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<Boolean> interrupted = new AtomicReference<>(null);

        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name("blocking")
                    .description("blocks until released or interrupted")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return ToolMetadata.builder().returnDirect(false).build();
        }

        @Override
        public String call(String toolInput) {
            entered.countDown();
            try {
                // Block "forever" — only an interrupt (from cancelGeneration) should release this.
                release.await();
                interrupted.set(false);
                return "completed normally";
            } catch (InterruptedException e) {
                interrupted.set(true);
                // Re-set so the worker's finally sees the interrupt too (matches what
                // BrowserBridgeClient would propagate as InterruptedIOException).
                Thread.currentThread().interrupt();
                throw new RuntimeException("blocked tool interrupted", e);
            }
        }
    }

    @Test
    void cancelInterruptsInFlightToolCall() throws Exception {
        SpringAiCloudBackend backend = new SpringAiCloudBackend(new ToolRequestingModel());
        BlockingToolCallback blockingTool = new BlockingToolCallback();
        backend.setToolCallbacks(List.of(blockingTool));

        List<AiChatMessage> history = new java.util.ArrayList<>(List.of(AiChatMessage.user("run the tool")));

        CountDownLatch errored = new CountDownLatch(1);
        AiStreamCallback cb = new AiStreamCallback() {
            @Override public void onToken(String token) { }
            @Override public void onToolCall(AiToolCall tc) { }
            @Override public void onToolResult(String id, AiToolResult r) { }
            @Override public void onComplete(String s, int t, double r) {
                fail("onComplete should NOT fire when cancelled mid-tool");
            }
            @Override public void onError(Throwable t) { errored.countDown(); }
        };

        backend.chat(history, 0.7f, 0.9f, 256, cb);

        // Wait until the worker is actually inside the blocking tool, then cancel.
        assertTrue(blockingTool.entered.await(5, TimeUnit.SECONDS),
                "tool should have been entered");
        backend.cancelGeneration();

        // The worker must unwind promptly via interrupt — NOT wait for the tool's own timeout
        // (which here is forever). 5s is far shorter than the pre-fix behaviour (wedged forever).
        assertTrue(errored.await(5, TimeUnit.SECONDS),
                "onError should fire promptly after cancel (worker was not interrupted)");
        assertTrue(blockingTool.interrupted.get(),
                "the in-flight tool call should have observed its interrupt");
        assertFalse(backend.isGenerating(),
                "generating flag must clear after a cancelled run so the next chat is not wedged");
    }

    @Test
    void backendIsReusableAfterCancel() throws Exception {
        // Regression guard: cancel sets `cancelled=true`; a singleton backend reused for the next
        // turn must re-arm (clear the flag) or every subsequent chat aborts immediately.
        SpringAiCloudBackend backend = new SpringAiCloudBackend(new ChatModel() {
            final AtomicInteger n = new AtomicInteger();
            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                if (n.incrementAndGet() == 1) {
                    AssistantMessage am = AssistantMessage.builder().content("").toolCalls(List.of(
                            new AssistantMessage.ToolCall("c", "function", "blocking", "{}"))).build();
                    return Flux.just(new ChatResponse(List.of(new Generation(am))));
                }
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
            }
            @Override
            public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
        });
        BlockingToolCallback blockingTool = new BlockingToolCallback();
        backend.setToolCallbacks(List.of(blockingTool));

        // First turn: cancel mid-tool.
        CountDownLatch firstErr = new CountDownLatch(1);
        backend.chat(new java.util.ArrayList<>(List.of(AiChatMessage.user("go"))),
                0.7f, 0.9f, 256, new AiStreamCallback() {
                    @Override public void onToken(String token) { }
                    @Override public void onToolCall(AiToolCall tc) { }
                    @Override public void onToolResult(String id, AiToolResult r) { }
                    @Override public void onComplete(String s, int t, double r) { }
                    @Override public void onError(Throwable t) { firstErr.countDown(); }
                });
        assertTrue(blockingTool.entered.await(5, TimeUnit.SECONDS));
        backend.cancelGeneration();
        assertTrue(firstErr.await(5, TimeUnit.SECONDS));
        // Release the first (interrupted) tool's latch so it doesn't linger.
        blockingTool.release.countDown();

        // Second turn: must complete normally — proves `cancelled` was re-armed.
        CountDownLatch secondDone = new CountDownLatch(1);
        backend.chat(new java.util.ArrayList<>(List.of(AiChatMessage.user("again"))),
                0.7f, 0.9f, 256, new AiStreamCallback() {
                    @Override public void onToken(String token) { }
                    @Override public void onToolCall(AiToolCall tc) { }
                    @Override public void onToolResult(String id, AiToolResult r) { }
                    @Override public void onComplete(String s, int t, double r) { secondDone.countDown(); }
                    @Override public void onError(Throwable t) { }
                });
        assertTrue(secondDone.await(5, TimeUnit.SECONDS),
                "second turn after a cancel must complete normally (cancelled flag should be re-armed)");
    }
}
