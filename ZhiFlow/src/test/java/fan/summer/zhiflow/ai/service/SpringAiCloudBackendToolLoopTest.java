package fan.summer.zhiflow.ai.service;

import fan.summer.zhiflow.api.ai.AiChatMessage;
import fan.summer.zhiflow.api.ai.AiServiceProvider;
import fan.summer.zhiflow.api.ai.AiStreamCallback;
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.ai.AiToolCall;
import fan.summer.zhiflow.api.ai.AiToolParam;
import fan.summer.zhiflow.api.ai.AiToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the manual tool loop drives exactly one round-trip per tool batch
 * and fires onToolCall/onToolResult on each call. Uses a scripted ChatModel
 * that returns a tool-call on the first stream and a final answer on the second.
 */
class SpringAiCloudBackendToolLoopTest {

    private static final class ScriptedChatModel implements ChatModel {
        final AtomicInteger callCount = new AtomicInteger(0);

        // First stream -> tool call; second stream -> final text.
        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            int n = callCount.incrementAndGet();
            if (n == 1) {
                AssistantMessage am = AssistantMessage.builder()
                        .content("")
                        .properties(Map.of())
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call_1", "function", "echo", "{\"text\":\"hi\"}")))
                        .build();
                return Flux.just(new ChatResponse(List.of(new Generation(am))));
            }
            AssistantMessage finalAm = new AssistantMessage("echo:hi");
            return Flux.just(new ChatResponse(List.of(new Generation(finalAm))));
        }

        @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
    }

    // Callbacks now run directly on the calling (virtual) thread — no FX toolkit needed.

    private AiTool echoTool;

    @BeforeEach void setupTool() {
        echoTool = new AiTool() {
            @Override public String getName()        { return "echo"; }
            @Override public String getDescription() { return "echo"; }
            @Override public List<AiToolParam> getParameters() {
                return List.of(AiToolParam.of("text", "string", "text", true));
            }
            @Override public AiToolResult execute(Map<String, Object> args) {
                return AiToolResult.success("echo:" + args.get("text"));
            }
        };
        AiServiceProvider.registerTool(echoTool);
    }

    @AfterEach void clearTool() { AiServiceProvider.clearTools(); }

    @Test
    void toolLoopFiresCallbackEvents() throws Exception {
        SpringAiCloudBackend backend = new SpringAiCloudBackend(new ScriptedChatModel());
        List<AiChatMessage> history = new java.util.ArrayList<>(List.of(AiChatMessage.user("ping")));

        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger toolCalls = new AtomicInteger(0);
        AtomicInteger toolResults = new AtomicInteger(0);
        AiStreamCallback cb = new AiStreamCallback() {
            @Override public void onToken(String token) { }
            @Override public void onToolCall(AiToolCall tc) { toolCalls.incrementAndGet(); }
            @Override public void onToolResult(String id, AiToolResult r) { toolResults.incrementAndGet(); }
            @Override public void onComplete(String s, int t, double r) { done.countDown(); }
            @Override public void onError(Throwable t) { done.countDown(); }
        };

        backend.chat(history, 0.7f, 0.9f, 256, cb);
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, toolCalls.get());
        assertEquals(1, toolResults.get());
        // history should now contain: [user, assistantWithTools, toolResult, assistant-final]
        assertEquals(4, history.size());
    }
}
