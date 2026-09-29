package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiStreamCallback;
import fan.summer.fengyu.ai.config.ChatModelConfig;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI observability wiring: with the Spring-managed registry bridged into
 * {@link ChatModelConfig}, Spring AI's {@code ToolCallingManager} emits a tool-call
 * observation for every executed tool — including on our user-controlled execution path
 * (the batch executor drives the manager per call). The scripted loop below runs one
 * real tool round and asserts the observation landed.
 */
class ChatModelObservabilityTest {

    private final List<String> events = new CopyOnWriteArrayList<>();

    /** Minimal recording registry: collects observation names on stop. */
    private final ObservationRegistry registry = new ObservationRegistry() {
        private final ObservationConfig config = new ObservationConfig().observationHandler(
                new ObservationHandler<>() {
                    @Override public boolean supportsContext(Observation.Context context) {
                        return true;
                    }

                    @Override public void onStop(Observation.Context context) {
                        events.add(String.valueOf(context.getName()));
                    }
                });

        @Override public ObservationConfig observationConfig() {
            return config;
        }

        @Override public Observation getCurrentObservation() {
            return Observation.NOOP;
        }

        @Override public Observation.Scope getCurrentObservationScope() {
            return null;
        }

        @Override public void setCurrentObservationScope(Observation.Scope scope) {
            // thread-local scopes are irrelevant to this recording test
        }
    };

    @AfterEach
    void restoreNoop() {
        ChatModelConfig.useObservationRegistry(ObservationRegistry.NOOP);
    }

    @Test
    void toolCallObservationsFlowThroughTheUserControlledLoop() throws Exception {
        ChatModelConfig.useObservationRegistry(registry);

        // Self-check: the recording registry fires for a hand-made observation.
        io.micrometer.observation.Observation
                .createNotStarted("self.check", registry)
                .observe(() -> "");
        assertTrue(events.contains("self.check"), "registry self-check failed: " + events);

        AtomicInteger attempts = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
                if (attempts.incrementAndGet() == 1) {
                    AssistantMessage am = AssistantMessage.builder()
                            .content("")
                            .toolCalls(List.of(new AssistantMessage.ToolCall(
                                    "call_1", "function", "echo", "{\"text\":\"hi\"}")))
                            .build();
                    return reactor.core.publisher.Flux.just(
                            new ChatResponse(List.of(new Generation(am))));
                }
                return reactor.core.publisher.Flux.just(new ChatResponse(
                        List.of(new Generation(new AssistantMessage("done")))));
            }
        };
        ToolCallback echo = new ChatClientToolLoopTest.EchoToolCallback();

        CountDownLatch done = new CountDownLatch(1);
        SpringAiCloudBackend backend = new SpringAiCloudBackend(model);
        backend.setToolCallbacks(List.of(echo));
        backend.chat(
                new java.util.ArrayList<>(List.of(AiChatMessage.user("hi"))), 0.7f, 0.9f, 256,
                new AiStreamCallback() {
                    @Override public void onToken(String fragment) { }
                    @Override public void onComplete(String s, int t, double r) { done.countDown(); }
                    @Override public void onError(Throwable t) { done.countDown(); }
                });

        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(2, attempts.get(), "the scripted round then the final answer");
        assertTrue(events.stream().anyMatch(name ->
                        name.toLowerCase(Locale.ROOT).contains("tool")),
                "a tool-call observation reached the registry: " + events);
    }
}
