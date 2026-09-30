package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiStreamCallback;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Streaming latency contract: tokens must reach the callback PROGRESSIVELY — each token
 * arrives within a bounded window of its production, not batched at stream end (the
 * reported live regression: "the chat box sticks, then everything appears at once").
 * The scripted model emits tokens with deliberate gaps; the callback records arrival
 * times. If the loop buffered, every arrival would cluster at the end.
 */
class ChatStreamingLatencyTest {

    @Test
    void tokensArriveProgressivelyNotBatchedAtTheEnd() throws Exception {
        int tokenCount = 8;
        long gapMs = 150;
        ChatModel model = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                Flux<ChatResponse> flow = Flux.just(
                        new ChatResponse(List.of(new Generation(new AssistantMessage("")))));
                for (int i = 0; i < tokenCount; i++) {
                    flow = flow.concatWith(Flux.just(new ChatResponse(
                            List.of(new Generation(new AssistantMessage("t" + i))))))
                            .delayElements(java.time.Duration.ofMillis(gapMs));
                }
                return flow;
            }
        };

        List<Long> arrivalNanos = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        new SpringAiCloudBackend(model).chat(
                new ArrayList<>(List.of(AiChatMessage.user("hi"))), 0.7f, 0.9f, 256,
                new AiStreamCallback() {
                    @Override public void onToken(String fragment) {
                        arrivalNanos.add(System.nanoTime());
                    }

                    @Override public void onComplete(String s, int t, double r) {
                        done.countDown();
                    }

                    @Override public void onError(Throwable t) {
                        done.countDown();
                    }
                });

        assertTrue(done.await(30, TimeUnit.SECONDS), "the turn completes");
        assertTrue(arrivalNanos.size() >= tokenCount,
                "every token reached the callback: " + arrivalNanos.size());

        // The LAST token must arrive BEFORE the turn completes (completion is terminal —
        // a batched delivery would show the last token at/after completion, because the
        // UI would have rendered nothing until then).
        long lastToken = arrivalNanos.get(arrivalNanos.size() - 1);
        long completion = System.nanoTime();
        assertTrue(completion - lastToken > 0);

        // Progressive pacing: the span between the 2nd and the last token must be on the
        // order of the production gaps (≥ 3 gaps). A batched-at-end delivery collapses
        // this span toward zero.
        long spanMs = TimeUnit.NANOSECONDS.toMillis(
                arrivalNanos.get(arrivalNanos.size() - 1) - arrivalNanos.get(1));
        assertTrue(spanMs >= gapMs * 3,
                "tokens arrive progressively (span " + spanMs + "ms for "
                        + tokenCount + " tokens spaced " + gapMs + "ms)");
    }
}
