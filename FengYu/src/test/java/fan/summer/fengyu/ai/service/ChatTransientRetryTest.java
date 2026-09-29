package fan.summer.fengyu.ai.service;

import com.openai.errors.OpenAIRetryableException;
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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Transient-error retry around the model stream — Spring Core's {@code RetryTemplate},
 * the same abstraction Spring AI's own {@code RetryUtils} builds its templates on,
 * driven by the cloud transport's policy (which classifies the official OpenAI SDK's
 * retryable marker). Three invariants: a pre-token retryable failure is retried and the
 * turn still completes; a post-token failure is NEVER retried (replaying a partially
 * streamed answer would duplicate it in the transcript); a persistently transient
 * stream fails the turn with the original cause after 1 initial + maxRetries(2)
 * attempts.
 */
class ChatTransientRetryTest {

    /** The policy backs off 500ms then 1s — the window must cover both sleeps. */
    private static final long WINDOW_SECONDS = 20;

    @Test
    void preTokenTransientFailureIsRetriedAndCompletes() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return attempts.incrementAndGet() == 1
                        ? Flux.error(new OpenAIRetryableException("upstream 5xx"))
                        : Flux.just(text("recovered"));
            }
        };
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> completed = new AtomicReference<>();
        AtomicReference<Throwable> failed = new AtomicReference<>();

        new SpringAiCloudBackend(model).chat(
                new ArrayList<>(List.of(AiChatMessage.user("hi"))), 0.7f, 0.9f, 256,
                new AiStreamCallback() {
                    @Override public void onToken(String fragment) { }
                    @Override public void onComplete(String s, int t, double r) { completed.set(s); done.countDown(); }
                    @Override public void onError(Throwable t) { failed.set(t); done.countDown(); }
                });

        assertTrue(done.await(WINDOW_SECONDS, TimeUnit.SECONDS));
        assertNull(failed.get(), "the retry salvages the turn");
        assertEquals("recovered", completed.get());
        assertEquals(2, attempts.get(), "exactly one retry after the pre-token transient failure");
    }

    @Test
    void postTokenFailureIsNeverRetried() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        List<String> tokens = Collections.synchronizedList(new ArrayList<>());
        ChatModel model = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                attempts.incrementAndGet();
                // One token reaches the UI, THEN the stream dies transiently — a retry
                // would replay "partial" into the transcript, so it must not happen.
                return Flux.just(text("partial"))
                        .concatWith(Flux.error(new OpenAIRetryableException("mid-stream")));
            }
        };
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failed = new AtomicReference<>();

        new SpringAiCloudBackend(model).chat(
                new ArrayList<>(List.of(AiChatMessage.user("hi"))), 0.7f, 0.9f, 256,
                new AiStreamCallback() {
                    @Override public void onToken(String fragment) { tokens.add(fragment); }
                    @Override public void onComplete(String s, int t, double r) { done.countDown(); }
                    @Override public void onError(Throwable t) { failed.set(t); done.countDown(); }
                });

        assertTrue(done.await(WINDOW_SECONDS, TimeUnit.SECONDS));
        assertEquals(1, attempts.get(), "already-emitted output suppresses the retry");
        assertEquals(List.of("partial"), tokens);
        assertTrue(failed.get().getCause() instanceof OpenAIRetryableException,
                "the original mid-stream error surfaces untouched");
    }

    @Test
    void persistentlyTransientStreamFailsAfterOnePlusMaxRetriesAttempts() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                attempts.incrementAndGet();
                return Flux.error(new OpenAIRetryableException("still 5xx"));
            }
        };
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failed = new AtomicReference<>();

        new SpringAiCloudBackend(model).chat(
                new ArrayList<>(List.of(AiChatMessage.user("hi"))), 0.7f, 0.9f, 256,
                new AiStreamCallback() {
                    @Override public void onToken(String fragment) { }
                    @Override public void onComplete(String s, int t, double r) { done.countDown(); }
                    @Override public void onError(Throwable t) { failed.set(t); done.countDown(); }
                });

        assertTrue(done.await(WINDOW_SECONDS, TimeUnit.SECONDS));
        assertEquals(3, attempts.get(), "1 initial attempt + maxRetries(2)");
        assertTrue(failed.get().getCause() instanceof OpenAIRetryableException);
    }

    private static ChatResponse text(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
