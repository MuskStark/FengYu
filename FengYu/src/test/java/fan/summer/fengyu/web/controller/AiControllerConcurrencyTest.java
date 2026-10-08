package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiServiceException;
import fan.summer.fengyu.ai.AiStreamCallback;
import fan.summer.fengyu.ai.ChatArtifactStore;
import fan.summer.fengyu.ai.ChatBackend;
import fan.summer.fengyu.ai.ChatFileContext.ActiveFileRef;
import fan.summer.fengyu.ai.ChatFileGrantService;
import fan.summer.fengyu.ai.ChatResourceScopeService;
import fan.summer.fengyu.ai.service.AiModeService;
import fan.summer.fengyu.ai.tools.ChatToolApprovalGate;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService;
import fan.summer.fengyu.web.StreamTicketService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 4.1.0 per-conversation streaming: turns of DIFFERENT conversations generate in
 * parallel (one active stream per conversation), a conversation serializes only its
 * own turns (queue + nextStreamId), and {@code POST /cancel} cancels exactly the turn
 * whose handle the stream captured — never a parallel conversation's.
 */
class AiControllerConcurrencyTest {

    @TempDir Path temp;
    private int rootSeq = 0;

    private static final String PLUGIN_ID = "test.writer";

    /** The controller plus the grant service it was wired with (the cleanup test needs it). */
    private record Fixture(AiController controller, ChatFileGrantService chatFiles) {}

    private Fixture fixtureParts(ChatBackend backend) throws Exception {
        Path pluginRoot = Files.createDirectories(temp.resolve("plugins"));
        Path pluginDir = Files.createDirectories(pluginRoot.resolve(PLUGIN_ID));
        Files.writeString(pluginDir.resolve("manifest.json"), """
            {"schemaVersion":2,"id":"%s","name":"Writer","description":"test","version":"1.0.0",
             "author":"test","icon":"test","category":"OTHER","ui":{"entry":"ui/index.html"},
             "backend":{"callTimeoutSeconds":60},"permissions":["files.read","files.write"],
             "official":false,"aiTools":[]}
            """.formatted(PLUGIN_ID));
        PluginFileGrantService files = new PluginFileGrantService(
                temp.resolve("grants-" + (++rootSeq)).toString());
        ChatFileGrantService chatFiles =
                new ChatFileGrantService(new PluginPackageService(pluginRoot.toString()), files);
        ChatResourceScopeService scopes = new ChatResourceScopeService(
                chatFiles, files, temp.resolve("copies-" + rootSeq));
        ChatArtifactStore artifacts =
                new ChatArtifactStore(files, temp.resolve("artifacts-" + rootSeq));
        @SuppressWarnings("unchecked")
        ObjectProvider<fan.summer.fengyu.ai.config.AiToolRegistry> provider =
                Mockito.mock(ObjectProvider.class);
        fan.summer.fengyu.security.SecurityContext security =
                Mockito.mock(fan.summer.fengyu.security.SecurityContext.class);
        Mockito.when(security.currentUserId()).thenReturn(1L);
        AiModeService aiMode = new AiModeService();
        aiMode.setService(backend);
        return new Fixture(new AiController(aiMode, new ChatToolApprovalGate(), chatFiles, files,
                new StreamTicketService(), provider, scopes, artifacts, security), chatFiles);
    }

    private AiController fixture(ChatBackend backend) throws Exception {
        return fixtureParts(backend).controller();
    }

    /** One legacy (scope-less) POST bound to a conversation; returns the full response. */
    private Map<String, Object> post(AiController controller, long conversationId) {
        return controller.chat(new AiController.ChatRequest(
                List.of(new AiController.ChatMessageDto("user", "hi")),
                null, null, null, null, null, null, conversationId, null), null);
    }

    /**
     * Minimal {@link ChatBackend}: {@code chat} registers the turn (handle + callback)
     * and returns immediately; the test drives completions and observes cancels itself.
     * Cancelling a handle fires that turn's {@code onError} synchronously — mirroring
     * the real driver, whose cancelled worker terminates the stream with an error — so
     * the controller's error terminal (slot release + queue discard) runs on cancel.
     */
    static final class ScriptedBackend implements ChatBackend {
        final AtomicInteger chatCalls = new AtomicInteger();
        final List<Handle> handles = new CopyOnWriteArrayList<>();
        final List<AiStreamCallback> callbacks = new CopyOnWriteArrayList<>();

        static final class Handle implements GenerationHandle {
            final AtomicInteger cancels = new AtomicInteger();
            private final AiStreamCallback callback;
            Handle(AiStreamCallback callback) { this.callback = callback; }
            @Override public void cancel() {
                cancels.incrementAndGet();
                callback.onError(new AiServiceException("cancelled"));
            }
        }

        @Override public void loadModel(Path modelPath) { }
        @Override public void unloadModel() { }
        @Override public boolean isReady() { return true; }
        @Override public Optional<String> getModelName() { return Optional.of("scripted"); }
        @Override public long getMemoryUsage() { return -1; }
        @Override public boolean isGenerating() { return !handles.isEmpty(); }
        @Override public void cancelGeneration() { handles.forEach(Handle::cancel); }

        @Override public GenerationHandle chat(List<AiChatMessage> history, AiStreamCallback callback)
                throws AiServiceException {
            return chat(history, 0.7f, 1.0f, 512, List.of(), callback);
        }

        @Override public GenerationHandle chat(List<AiChatMessage> history, float temperature,
                float topP, int maxTokens, List<ActiveFileRef> activeFileRefs,
                AiStreamCallback callback) throws AiServiceException {
            chatCalls.incrementAndGet();
            Handle handle = new Handle(callback);
            handles.add(handle);
            callbacks.add(callback);
            return handle;
        }

        /** Completes turn {@code index}'s model stream like the real driver would. */
        void complete(int index, String text) {
            AiStreamCallback callback = callbacks.get(index);
            callback.onToken(text);
            callback.onComplete(text, 1, 1.0);
        }
    }

    @Test
    void twoConversationsStreamInParallelAndCancelIndependently() throws Exception {
        ScriptedBackend backend = new ScriptedBackend();
        AiController controller = fixture(backend);
        String stream1 = (String) post(controller, 1L).get("streamId");
        String stream2 = (String) post(controller, 2L).get("streamId");

        controller.stream(stream1);
        controller.stream(stream2);
        assertEquals(2, backend.chatCalls.get(),
                "turns of different conversations must generate in parallel");
        assertEquals(2, backend.handles.size());

        Map<String, Object> first = controller.cancel(stream1);
        assertEquals(Boolean.TRUE, first.get("ok"));
        assertEquals(1, backend.handles.get(0).cancels.get(),
                "cancel(stream1) cancels turn 1's own handle");
        assertEquals(0, backend.handles.get(1).cancels.get(),
                "the parallel conversation's turn is untouched");

        Map<String, Object> second = controller.cancel(stream2);
        assertEquals(Boolean.TRUE, second.get("ok"),
                "stream2 stayed an active generation the whole time");
        assertEquals(1, backend.handles.get(1).cancels.get());

        assertEquals(Boolean.FALSE, controller.cancel(stream1).get("ok"),
                "a cancelled stream is no longer active");
        // The cancelled turns' error terminals (fired by the fake's handle.cancel, like
        // the real driver) released their conversations' slots: an immediate same-
        // conversation POST must NOT park — a leaked slot here is the "conversation
        // bricked after stop" failure mode.
        assertEquals(Boolean.FALSE, post(controller, 1L).get("queued"),
                "conversation 1's slot is free after its cancelled terminal");
        assertEquals(Boolean.FALSE, post(controller, 2L).get("queued"),
                "conversation 2's slot is free after its cancelled terminal");
    }

    @Test
    void sameConversationSerializesItsOwnTurnsButNeverBlocksOthers() throws Exception {
        ScriptedBackend backend = new ScriptedBackend();
        AiController controller = fixture(backend);
        String first = (String) post(controller, 1L).get("streamId");
        controller.stream(first);
        assertEquals(1, backend.chatCalls.get());

        Map<String, Object> queued = post(controller, 1L);
        assertEquals(Boolean.TRUE, queued.get("queued"),
                "a same-conversation POST parks in the conversation's queue");
        assertEquals(1, backend.chatCalls.get(), "the queued turn did not start");

        Map<String, Object> other = post(controller, 2L);
        assertEquals(Boolean.FALSE, other.get("queued"),
                "a different conversation sends freely while conversation 1 streams");

        // The first turn's terminal releases the conversation's active slot…
        backend.complete(0, "done-1");
        // …and the parked successor opens cleanly, without a park-back error.
        controller.stream((String) queued.get("streamId"));
        assertEquals(2, backend.chatCalls.get(),
                "the successor starts after its predecessor's terminal");
        backend.complete(1, "done-2");

        // The slot is free again: the next same-conversation POST starts immediately.
        assertEquals(Boolean.FALSE, post(controller, 1L).get("queued"));
    }

    @Test
    void racingSameConversationStreamOpenParksBackUntilTheActiveTerminal() throws Exception {
        ScriptedBackend backend = new ScriptedBackend();
        AiController controller = fixture(backend);
        String first = (String) post(controller, 1L).get("streamId");
        controller.stream(first);
        String second = (String) post(controller, 1L).get("streamId");

        // A frontend opening the QUEUED stream anyway (the race the park-back guards):
        // the conversation is busy, so the open parks it back instead of starting it.
        controller.stream(second);
        assertEquals(1, backend.chatCalls.get(),
                "the racing open must not start a second turn of the same conversation");

        backend.complete(0, "done-1");   // the terminal pops the successor (nextStreamId)
        controller.stream(second);       // the successor named by done opens for real
        assertEquals(2, backend.chatCalls.get());
        backend.complete(1, "done-2");
    }

    /**
     * Queue-full contract: once the conversation's queue holds MAX_QUEUE_PER_CONVERSATION
     * parked turns, an extra POST is answered {@code queued:false, queueFull:true} — the
     * client learns the queue is full WITHOUT opening the stream (which would only earn a
     * conversation_busy park-back error).
     */
    @Test
    void aQueueFullPostAnswersQueueFullTrueInsteadOfParking() throws Exception {
        ScriptedBackend backend = new ScriptedBackend();
        AiController controller = fixture(backend);
        String first = (String) post(controller, 1L).get("streamId");
        controller.stream(first);
        for (int i = 0; i < 3; i++) {
            assertEquals(Boolean.TRUE, post(controller, 1L).get("queued"),
                    "turn " + i + " parks while the queue still has room");
        }

        Map<String, Object> overflow = post(controller, 1L);
        assertEquals(Boolean.FALSE, overflow.get("queued"), "the cap leaves no room to park");
        assertEquals(Boolean.TRUE, overflow.get("queueFull"),
                "the overflow response must name the queue-full condition");
        // The overflowed turn was NOT parked: it is addressable by its own streamId.
        assertNotNull(overflow.get("streamId"));
    }

    /**
     * Lost-terminal wedge: the active turn completes and pops a successor, but the
     * {@code done} event never left the wire — SseCallback then runs the disconnect
     * cleanup (pinned in {@code AiControllerSseCallbackTest}), which must drop BOTH the
     * remaining queued turns AND the popped successor. Pre-fix, the leftover queue had no
     * active generation left to pop it: every further POST parked behind a dead queue
     * (conversation bricked until the 10-minute sweep).
     */
    @Test
    void aLostDoneTerminalUnwedgesTheConversationQueueAndReclaimsTheSuccessor() throws Exception {
        ScriptedBackend backend = new ScriptedBackend();
        Fixture fixture = fixtureParts(backend);
        AiController controller = fixture.controller();
        String first = (String) post(controller, 1L).get("streamId");
        controller.stream(first);
        String successor = (String) post(controller, 1L).get("streamId");
        String tail = (String) post(controller, 1L).get("streamId");

        // The active turn's success terminal pops the successor (queue keeps the tail).
        backend.complete(0, "done-1");

        // ...and the done event never left the wire: the SseCallback's disconnect cleanup
        // runs — replicate its single call here with the controller's own cleanup method.
        java.util.concurrent.atomic.AtomicReference<String> popped =
                new java.util.concurrent.atomic.AtomicReference<>(successor);
        controller.cleanupDisconnect(
                new AiController.TurnLease(fixture.chatFiles(), null, null, null, null, List.of()),
                first, new AiController.ActiveGeneration(backend, 1L),
                new AiController.PendingTurn(List.of(), List.of(), List.of(), null, null,
                        java.time.Instant.now(), List.of(), null, null, null, 1L, List.of()),
                popped);

        // The conversation unwedges: no active generation, no queue — the next POST starts.
        assertEquals(Boolean.FALSE, post(controller, 1L).get("queued"),
                "the dead queue must be dropped, not left wedged until the sweep");

        // The popped successor AND the queued tail were reclaimed, not orphaned: opening
        // either is a terminal unknown_stream, never a replay.
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(controller).build();
        for (String orphan : List.of(successor, tail)) {
            var result = mvc.perform(org.springframework.test.web.servlet.request
                            .MockMvcRequestBuilders.get("/api/ai/stream")
                            .param("streamId", orphan)
                            .accept(org.springframework.http.MediaType.TEXT_EVENT_STREAM))
                    .andExpect(org.springframework.test.web.servlet.result
                            .MockMvcResultMatchers.request().asyncStarted())
                    .andReturn();
            String body = mvc.perform(org.springframework.test.web.servlet.request
                            .MockMvcRequestBuilders.asyncDispatch(result))
                    .andReturn().getResponse().getContentAsString();
            assertTrue(body.contains("\"code\":\"unknown_stream\""),
                    "the reclaimed turn must not be openable: " + body);
        }
    }
}
