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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M-2 regression: a generation the user cancels MID-STREAM (tokens still arriving, stream never
 * completing) must terminate via onError — not onComplete. Reactor's cancel signal delivers
 * neither onError nor onComplete downstream, so before the fix the loop fell through to the
 * no-tool-calls branch and reported the PARTIAL text as a successful answer; through
 * AiController's TurnLease that also exported the cancelled turn's staging into the user's
 * named output directory.
 */
class ChatCancelMidStreamTest {

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

    /** Emits one text delta, then stays open forever — a provider stream mid-flight at cancel. */
    static final class NeverCompletingModel implements ChatModel {
        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.create(sink -> sink.next(new ChatResponse(List.of(
                    new Generation(new AssistantMessage("partial answer"))))));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void cancelMidStreamTerminatesAsErrorNotSuccess() throws Exception {
        SpringAiCloudBackend backend = new SpringAiCloudBackend(new NeverCompletingModel());
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicBoolean cancelledOnce = new AtomicBoolean(false);
        CountDownLatch errored = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AiStreamCallback cb = new AiStreamCallback() {
            @Override public void onToken(String token) {
                // The user hits "stop" the moment the first partial token lands.
                if (cancelledOnce.compareAndSet(false, true)) backend.cancelGeneration();
            }
            @Override public void onToolCall(AiToolCall tc) { }
            @Override public void onToolResult(String id, AiToolResult r) { }
            @Override public void onComplete(String s, int t, double r) {
                completed.set(true);
            }
            @Override public void onError(Throwable t) {
                error.set(t);
                errored.countDown();
            }
        };

        backend.chat(new ArrayList<>(List.of(AiChatMessage.user("hello"))), 0.7f, 0.9f, 256, cb);

        assertTrue(errored.await(5, TimeUnit.SECONDS),
                "a cancelled stream must terminate via onError");
        assertEquals("cancelled", String.valueOf(error.get().getMessage()));
        assertFalse(completed.get(),
                "partial text from a cancelled turn must NOT be reported as a successful answer");
        assertFalse(backend.isGenerating(),
                "the generation slot must be released so the next chat is not wedged");
    }
}
