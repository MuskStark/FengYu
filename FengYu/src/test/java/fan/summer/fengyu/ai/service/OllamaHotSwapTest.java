package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiConfigService;
import fan.summer.fengyu.ai.AiStreamCallback;
import fan.summer.fengyu.ai.AiToolCall;
import fan.summer.fengyu.ai.AiToolResult;
import fan.summer.fengyu.database.repository.AppSettingRepository;
import fan.summer.fengyu.security.SecurityContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the Ollama hot-swap race: a settings save while a local-mode turn is
 * streaming calls {@code AiModeService.switchMode → unloadModel()} on the OLD backend.
 * {@code unloadModel} used to null {@code chatModel} unconditionally, and the tool loop
 * re-reads {@code transport.chatModel()} every round — the in-flight turn died with an
 * NPE (cloud turns survive the same swap, their {@code unloadModel} is a no-op). The
 * fields are now only cleared once no live driver still reads them.
 */
class OllamaHotSwapTest {

    @BeforeAll
    static void initConfigInstance() throws Exception {
        AppSettingRepository repo = Mockito.mock(AppSettingRepository.class);
        SecurityContext ctx = Mockito.mock(SecurityContext.class);
        Mockito.when(repo.findByUserIdAndSettingKey(Mockito.anyLong(), Mockito.anyString()))
                .thenThrow(new RuntimeException("no db in unit test"));
        java.lang.reflect.Field f = AiConfigService.class.getDeclaredField("INSTANCE");
        f.setAccessible(true);
        f.set(null, new AiConfigService(repo, ctx));
    }

    /**
     * Streams one partial token, then parks until the test releases it — the model is
     * mid-answer at the moment the (simulated) settings save lands.
     */
    static final class ReleasableModel implements ChatModel {
        final CountDownLatch parked = new CountDownLatch(1);
        final AtomicBoolean released = new AtomicBoolean(false);

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.create(sink -> {
                parked.countDown();
                try {
                    while (!released.get()) Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
                sink.complete();
            });
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void configSaveMidLocalTurnDoesNotKillTheInFlightTurn() throws Exception {
        OllamaLocalBackend backend = new OllamaLocalBackend();
        ReleasableModel model = new ReleasableModel();
        chatModelField().set(backend, model);
        assertTrue(backend.isReady(), "a seeded chatModel makes the backend ready");

        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        backend.chat(new java.util.ArrayList<>(List.of(AiChatMessage.user("hello"))),
                0.7f, 0.9f, 256, new AiStreamCallback() {
                    @Override public void onToken(String fragment) { }
                    @Override public void onToolCall(AiToolCall tc) { }
                    @Override public void onToolResult(String id, AiToolResult r) { }
                    @Override public void onComplete(String s, int t, double r) {
                        completed.countDown();
                    }
                    @Override public void onError(Throwable t) {
                        failure.set(t);
                        completed.countDown();
                    }
                });

        // The turn is parked mid-stream (worker registered in liveDrivers).
        assertTrue(model.parked.await(5, TimeUnit.SECONDS), "the stream started");
        assertTrue(backend.isGenerating(), "the turn is live when the settings save lands");
        // Exactly what AiModeService.switchMode does on a hot-swap.
        backend.unloadModel();

        model.released.set(true);
        assertTrue(completed.await(5, TimeUnit.SECONDS),
                "the in-flight turn must survive a config hot-swap");
        assertEquals(null, failure.get(), "the turn completed, not errored: " + failure.get());

        // Once no driver is live, unloadModel clears the fields for real.
        assertFalse(backend.isGenerating());
        backend.unloadModel();
        assertFalse(backend.isReady(), "idle unload still releases the model");
        assertEquals(null, chatModelField().get(backend));
    }

    private static Field chatModelField() throws NoSuchFieldException {
        Field field = OllamaLocalBackend.class.getDeclaredField("chatModel");
        field.setAccessible(true);
        return field;
    }
}
