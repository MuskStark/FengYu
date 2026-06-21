package fan.summer.ai.adapter;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import fan.summer.api.ai.AiStreamCallback;
import fan.summer.api.ai.AiToolCall;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StreamingResponseHandlerBridgeTest {

    /** Captures every callback invocation for assertions. */
    static class CapturingCallback implements AiStreamCallback {
        final List<String> tokens = new ArrayList<>();
        String completeResponse;
        Throwable error;
        AiToolCall toolCall;
        int toolCallCount = 0;

        public void onToken(String fragment) { tokens.add(fragment); }
        public void onComplete(String fullResponse, int tokensGenerated, double tokensPerSecond) {
            this.completeResponse = fullResponse;
        }
        public void onError(Throwable error) { this.error = error; }
        public void onToolCall(AiToolCall toolCall) { this.toolCall = toolCall; toolCallCount++; }
    }

    @BeforeAll
    static void initFx() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyStarted) {
            // Toolkit already initialized — fine
        }
    }

    @Test
    void forwardsPartialTokens() {
        CapturingCallback cb = new CapturingCallback();
        StreamingResponseHandlerBridge bridge = new StreamingResponseHandlerBridge(cb);

        bridge.onPartialResponse("Hello");
        bridge.onPartialResponse(", ");
        bridge.onPartialResponse("world");

        // Platform.runLater is async; wait for the FX queue to drain.
        waitForFx();

        assertEquals(List.of("Hello", ", ", "world"), cb.tokens);
        assertNull(cb.completeResponse);
    }

    @Test
    void completesWithPlainText() {
        CapturingCallback cb = new CapturingCallback();
        StreamingResponseHandlerBridge bridge = new StreamingResponseHandlerBridge(cb);

        bridge.onPartialResponse("Hi");
        ChatResponse resp = ChatResponse.builder()
            .aiMessage(AiMessage.from("Hi"))
            .build();
        bridge.onCompleteResponse(resp);

        waitForFx();

        assertEquals("Hi", cb.completeResponse);
    }

    @Test
    void completesWithToolRequests() {
        CapturingCallback cb = new CapturingCallback();
        StreamingResponseHandlerBridge bridge = new StreamingResponseHandlerBridge(cb);

        ChatResponse resp = ChatResponse.builder()
            .aiMessage(AiMessage.from("", List.of(
                ToolExecutionRequest.builder()
                    .id("call_1").name("get_weather").arguments("{\"city\":\"Paris\"}")
                    .build()
            )))
            .build();
        bridge.onCompleteResponse(resp);

        waitForFx();

        assertNull(cb.completeResponse);
        assertEquals(1, bridge.pendingToolCalls().size());
        AiToolCall tc = bridge.pendingToolCalls().get(0);
        assertEquals("get_weather", tc.name());
        // AiToolCall.arguments() is Map<String,Object> per Task 2 — verify city parsed correctly
        assertEquals("Paris", ((Map<String, Object>) tc.arguments()).get("city"));
    }

    @Test
    void forwardsError() {
        CapturingCallback cb = new CapturingCallback();
        StreamingResponseHandlerBridge bridge = new StreamingResponseHandlerBridge(cb);

        bridge.onError(new RuntimeException("boom"));

        waitForFx();

        assertNotNull(cb.error);
        assertEquals("boom", cb.error.getMessage());
    }

    /** Drains the JavaFX event queue so Platform.runLater callbacks have fired. */
    private static void waitForFx() {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        Platform.runLater(latch::countDown);
        try {
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
