package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.api.ai.AiChatMessage;
import fan.summer.fengyu.api.ai.AiStreamCallback;
import fan.summer.fengyu.api.ai.AiToolCall;
import fan.summer.fengyu.api.ai.AiToolResult;
import fan.summer.fengyu.ai.tools.ApprovalRequiredToolCallback;
import fan.summer.fengyu.ai.tools.ChatToolApprovalGate;
import org.junit.jupiter.api.Test;
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
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the new {@code SpringAiCloudBackend} tool loop, now driven by Spring AI's
 * non-deprecated {@link org.springframework.ai.model.tool.ToolCallingManager} (built on a
 * {@link ChatModel}), fires the full {@link AiStreamCallback} contract:
 * {@code onToken} / {@code onToolCall} / {@code onToolResult} / {@code onComplete}.
 *
 * <p>Uses a scripted {@link ChatModel} that requests one {@code echo} tool call on the
 * first stream and returns a final answer on the second, plus a hand-rolled
 * {@link ToolCallback} registered via {@code setToolCallbacks(...)}.
 */
class ChatClientToolLoopTest {

    /** First stream → assistant message requesting tool "echo"; second stream → final text. */
    static final class ScriptedChatModel implements ChatModel {
        final AtomicInteger callCount = new AtomicInteger(0);

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            int n = callCount.incrementAndGet();
            if (n == 1) {
                AssistantMessage am = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call_1", "function", "echo", "{\"text\":\"hi\"}")))
                        .build();
                return Flux.just(new ChatResponse(List.of(new Generation(am))));
            }
            AssistantMessage finalAm = new AssistantMessage("echo:hi");
            return Flux.just(new ChatResponse(List.of(new Generation(finalAm))));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
        }
    }

    /** Minimal ToolCallback: name "echo", echoes the raw input prefixed with "echo:". */
    static final class EchoToolCallback implements ToolCallback {
        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name("echo")
                    .description("echoes the provided text back")
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}")
                    .build();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return ToolMetadata.builder().returnDirect(false).build();
        }

        @Override
        public String call(String toolInput) {
            return "echo:" + toolInput;
        }
    }

    @Test
    void toolLoopFiresTokenToolCallToolResultAndComplete() throws Exception {
        SpringAiCloudBackend backend = new SpringAiCloudBackend(new ScriptedChatModel());
        backend.setToolCallbacks(List.of(new EchoToolCallback()));

        List<AiChatMessage> history = new java.util.ArrayList<>(List.of(AiChatMessage.user("ping")));

        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger tokens = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        AtomicInteger toolResults = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        final StringBuilder finalText = new StringBuilder();

        AiStreamCallback cb = new AiStreamCallback() {
            @Override public void onToken(String token) { tokens.incrementAndGet(); }
            @Override public void onToolCall(AiToolCall tc) { toolCalls.incrementAndGet(); }
            @Override public void onToolResult(String id, AiToolResult r) { toolResults.incrementAndGet(); }
            @Override public void onComplete(String s, int t, double r) {
                finalText.append(s);
                completed.incrementAndGet();
                done.countDown();
            }
            @Override public void onError(Throwable t) { done.countDown(); }
        };

        backend.chat(history, 0.7f, 0.9f, 256, cb);
        assertTrue(done.await(5, TimeUnit.SECONDS), "onComplete should fire within timeout");
        assertEquals(1, completed.get(), "onComplete should fire exactly once");
        assertTrue(toolCalls.get() >= 1, "onToolCall should fire (got " + toolCalls.get() + ")");
        assertTrue(toolResults.get() >= 1, "onToolResult should fire (got " + toolResults.get() + ")");
        assertTrue(tokens.get() >= 1, "onToken should fire for the final text (got " + tokens.get() + ")");
        assertEquals("echo:hi", finalText.toString());
        // History: [user, assistantWithTools, toolResult, assistant-final]
        assertEquals(4, history.size(), "history should record user + assistant-with-tools + toolResult + final");
    }

    @Test
    void noToolPromptStillStreamsTokensAndCompletes() throws Exception {
        // A model that never requests tools and just returns final text on the first stream.
        ChatModel plainModel = new ChatModel() {
            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                AssistantMessage am = new AssistantMessage("hello there");
                return Flux.just(new ChatResponse(List.of(new Generation(am))));
            }
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }
        };
        SpringAiCloudBackend backend = new SpringAiCloudBackend(plainModel);
        // No tools injected → options stay null, the no-tool path is exercised.
        List<AiChatMessage> history = new java.util.ArrayList<>(List.of(AiChatMessage.user("hi")));

        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger tokens = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        AiStreamCallback cb = new AiStreamCallback() {
            @Override public void onToken(String token) { tokens.incrementAndGet(); }
            @Override public void onToolCall(AiToolCall tc) { toolCalls.incrementAndGet(); }
            @Override public void onComplete(String s, int t, double r) { done.countDown(); }
            @Override public void onError(Throwable t) { done.countDown(); }
        };

        backend.chat(history, 0.7f, 0.9f, 256, cb);
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(tokens.get() >= 1, "onToken should fire");
        assertEquals(0, toolCalls.get(), "no onToolCall should fire without tools");
        assertEquals("hello there", history.get(history.size() - 1).content());
    }

    @Test
    void sensitiveToolWaitsForChatApprovalBeforeInvocation() throws Exception {
        SpringAiCloudBackend backend = new SpringAiCloudBackend(new ScriptedChatModel());
        ChatToolApprovalGate gate = new ChatToolApprovalGate();
        AtomicInteger invocations = new AtomicInteger();
        ApprovalRequiredToolCallback sensitiveEcho = new ApprovalRequiredToolCallback() {
            private final ToolDefinition definition = new EchoToolCallback().getToolDefinition();
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public String call(String input) {
                invocations.incrementAndGet();
                return "echo:" + input;
            }
        };
        backend.setToolCallbacks(List.of(sensitiveEcho));
        backend.setToolApprovalGate(gate);

        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger approvalRequests = new AtomicInteger();
        backend.chat(new java.util.ArrayList<>(List.of(AiChatMessage.user("ping"))),
                0.7f, 0.9f, 256, new AiStreamCallback() {
                    @Override public void onToken(String token) {}
                    @Override public void onToolApprovalRequired(
                            String approvalId, AiToolCall call, Instant expiresAt) {
                        approvalRequests.incrementAndGet();
                        assertEquals(0, invocations.get(), "tool ran before approval");
                        assertTrue(gate.resolve(approvalId, true));
                    }
                    @Override public void onComplete(String s, int t, double r) { done.countDown(); }
                    @Override public void onError(Throwable t) { done.countDown(); }
                });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, approvalRequests.get());
        assertEquals(1, invocations.get());
    }
}
