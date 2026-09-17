package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.ChatArtifactStore;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scoped chat turns under the send-transaction model: the POST /api/ai/chat gate that resolves
 * resources through the scope registry, the sendId commit bracket (idempotent — one message,
 * one lease, one execution per send), text-path adoption, output-target staging, and the
 * terminal save closure. Server halves of acceptance rows B01–B04, E05/E06, A08, and C01's
 * wiring.
 */
class AiControllerScopedChatTest {

    private static final String PLUGIN_ID = "test.writer";
    private static final long USER = 1L;

    @TempDir Path temp;
    private int rootSeq = 0;

    private Fixture fixture() throws Exception {
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
        Mockito.when(security.currentUserId()).thenReturn(USER);
        AiController controller = new AiController(Mockito.mock(AiModeService.class),
                new ChatToolApprovalGate(), chatFiles, files, new StreamTicketService(),
                provider, scopes, artifacts, security);
        return new Fixture(controller, scopes, artifacts, files, chatFiles);
    }

    private record Fixture(AiController controller, ChatResourceScopeService scopes,
            ChatArtifactStore artifacts, PluginFileGrantService files, ChatFileGrantService chatFiles) {}

    /** Prepares and commits one native attachment through the full send protocol. */
    private String sendWithAttachment(Fixture f, String scope, String sendId, Path file)
            throws Exception {
        f.scopes().prepareSend(scope, USER, sendId, List.of(
                new ChatResourceScopeService.NativeAttachment("att_1", file.toString(), "file")));
        f.controller().chat(new AiController.ChatRequest(
                List.of(new AiController.ChatMessageDto("user", "summarize")),
                null, null, null, null, scope, List.of(), null, sendId), null);
        return f.scopes().snapshot(scope, USER).resources().get(0).resourceId();
    }

    /** Prepares an empty send (the "continue" turn: no new attachments, idempotence only). */
    private void prepareEmptySend(Fixture f, String scope, String sendId) {
        f.scopes().prepareSend(scope, USER, sendId, List.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void scopedTurnResolvesResourcesServerSideAndNeverEchoesRawRefs() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(USER);
        Path doc = Files.writeString(temp.resolve("scoped.txt"), "data");
        String resourceId = sendWithAttachment(f, scope, "send_1", doc);
        prepareEmptySend(f, scope, "send_2");

        Map<String, Object> response = f.controller().chat(new AiController.ChatRequest(
                List.of(new AiController.ChatMessageDto("user", "continue")),
                null, null, null, null, scope, List.of(resourceId), 5L, "send_2"), null);

        assertEquals(List.of(), response.get("activeFileRefs"),
                "a scoped response carries aggregated records, never raw grants");
        List<Map<String, Object>> resources = (List<Map<String, Object>>) response.get("resources");
        assertEquals(1, resources.size());
        assertEquals(resourceId, resources.get(0).get("resourceId"));
        assertFalse(resources.get(0).containsKey("refs"), "grant internals never reach the client");
        assertEquals(5L, f.scopes().conversationIdOf(scope), "the turn binds the conversation id");
        // Both pending turns hold their leases until a stream terminal releases them: the
        // first send's and this continue turn's (no SSE was opened in this unit test).
        assertEquals(2, f.scopes().activeLeaseCount(scope));
    }

    @Test
    void e06_aRetriedCommittedSendReplaysTheSameStreamWithoutANewLease() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(USER);
        Path doc = Files.writeString(temp.resolve("replay.txt"), "x");
        String resourceId = sendWithAttachment(f, scope, "send_1", doc);
        prepareEmptySend(f, scope, "send_2");

        // "继续" turn committed once…
        Map<String, Object> first = f.controller().chat(new AiController.ChatRequest(
                List.of(new AiController.ChatMessageDto("user", "continue")),
                null, null, null, null, scope, List.of(resourceId), null, "send_2"), null);
        int leases = f.scopes().activeLeaseCount(scope);

        // …the response was lost, the client retries the SAME sendId: replay, not resend.
        Map<String, Object> retried = f.controller().chat(new AiController.ChatRequest(
                List.of(new AiController.ChatMessageDto("user", "continue")),
                null, null, null, null, scope, List.of(resourceId), null, "send_2"), null);

        assertEquals(first.get("streamId"), retried.get("streamId"),
                "E06: one execution per sendId, never a duplicate message");
        assertEquals(leases, f.scopes().activeLeaseCount(scope),
                "the replay must not mint a second lease");
    }

    @Test
    void scopedTurnRejectsRawRefsAndForeignResources() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(USER);
        Path doc = Files.writeString(temp.resolve("foreign.txt"), "x");
        String resourceId = sendWithAttachment(f, scope, "send_1", doc);
        var rawRef = new AiController.ActiveFileRefDto(PLUGIN_ID,
                f.files().grantNative(PLUGIN_ID, doc.toString(), "file", "read"));

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> f.controller().chat(new AiController.ChatRequest(
                        List.of(new AiController.ChatMessageDto("user", "hi")),
                        List.of(rawRef), null, null, null, scope, List.of(), null, null), null),
                "scoped and raw ref styles are mutually exclusive");

        assertThrows(IllegalArgumentException.class,
                () -> f.scopes().acquireLease("cs_someone_else", USER, List.of(resourceId)),
                "B01: an id from another scope must be rejected at the gate");
    }

    @Test
    @SuppressWarnings("unchecked")
    void typedPathsBecomeScopeOwnedResourcesUsableByTheNextTurn() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(USER);
        Path doc = Files.writeString(temp.resolve("typed.txt"), "data");

        Map<String, Object> first = f.controller().chat(new AiController.ChatRequest(
                List.of(new AiController.ChatMessageDto("user", "read " + doc)),
                null, null, null, null, scope, List.of(), null, null), null);
        List<Map<String, Object>> adopted = (List<Map<String, Object>>) first.get("resources");
        assertEquals(1, adopted.size(), "a typed path is adopted as a registry resource");

        // The follow-up turn references it by id — the resource outlives the turn that typed it.
        String resourceId = (String) adopted.get(0).get("resourceId");
        prepareEmptySend(f, scope, "send_next");
        f.controller().chat(new AiController.ChatRequest(
                List.of(new AiController.ChatMessageDto("user", "continue")),
                null, null, null, null, scope, List.of(resourceId), null, "send_next"), null);
    }

    @Test
    void aFailedTurnRollsTheSendBackSoTheDraftIsAllThatRemains() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(USER);
        Path doc = Files.writeString(temp.resolve("rollback.txt"), "x");
        f.scopes().prepareSend(scope, USER, "send_1", List.of(
                new ChatResourceScopeService.NativeAttachment("att_1", doc.toString(), "file")));

        // A resource id from outside the scope makes lease acquisition fail AFTER beginCommit
        // already moved the prepared copies in — the commit bracket must roll them back out.
        assertThrows(IllegalArgumentException.class,
                () -> f.controller().chat(new AiController.ChatRequest(
                        List.of(new AiController.ChatMessageDto("user", "x")),
                        null, null, null, null, scope, List.of("res_someone_elses"), null,
                        "send_1"), null));

        assertTrue(f.scopes().snapshot(scope, USER).resources().isEmpty(),
                "E04-at-commit: a failed turn leaves no half-committed resources");
        assertEquals("failed", f.scopes().sendStatus(scope, USER, "send_1").state());
    }

    @Test
    void abandonedPostReleasesItsLeaseOnSweep() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(USER);
        Path doc = Files.writeString(temp.resolve("sweep.txt"), "x");
        String resourceId = sendWithAttachment(f, scope, "send_1", doc);
        prepareEmptySend(f, scope, "send_2");

        f.controller().chat(new AiController.ChatRequest(
                List.of(new AiController.ChatMessageDto("user", "hi")),
                null, null, null, null, scope, List.of(resourceId), null, "send_2"), null);
        assertEquals(2, f.scopes().activeLeaseCount(scope),
                "each pending POST pins its own lease until a terminal or sweep releases it");

        f.controller().sweepExpiredPendingTurns(java.time.Instant.now().plusSeconds(1));

        assertEquals(0, f.scopes().activeLeaseCount(scope), "B03: no orphaned execution lease");
    }

    @Test
    void outputTargetStagingSavesIntoTheTargetAtTheSuccessTerminal() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(USER);
        Path target = Files.createDirectories(temp.resolve("scope-out"));
        f.scopes().setOutputTarget(scope, USER, target.toString());
        f.scopes().bindConversation(scope, USER, 11L);
        prepareEmptySend(f, scope, "send_1");

        Map<String, Object> response = f.controller().chat(new AiController.ChatRequest(
                List.of(new AiController.ChatMessageDto("user", "make the report")),
                null, null, null, null, scope, List.of(), 11L, "send_1"), null);
        assertEquals(1, f.files().writablePaths(PLUGIN_ID).size(),
                "control: the turn minted one write staging root for its output target");

        // The success terminal: collect staging → auto-save into the captured target. A second
        // preparation stands in for the pending turn's (private) staged list.
        ChatFileGrantService.StagingPreparation preparation =
                f.chatFiles().prepareStagingForTargets(List.of(target));
        assertEquals(1, preparation.staged().size());
        Path stagingDir = f.files().resolve(PLUGIN_ID,
                preparation.staged().get(0).stagingRef().id());
        Files.writeString(stagingDir.resolve("报表.xlsx"), "generated");
        ChatResourceScopeService.Lease lease = f.scopes().acquireLease(scope, USER, List.of());

        new AiController.TurnLease(f.chatFiles(), f.artifacts(), f.scopes(), scope,
                lease.leaseId(), preparation.staged()).complete();

        assertEquals("generated", Files.readString(target.resolve("报表.xlsx")),
                "C01: the generated file is saved locally with its real content");
        var artifacts = f.artifacts().listByScope(scope);
        assertEquals(1, artifacts.size());
        assertEquals(ChatArtifactStore.STATE_SAVED, artifacts.get(0).state());
        assertEquals(target.resolve("报表.xlsx").toString(), artifacts.get(0).savedPath());
        assertEquals(0, f.scopes().activeLeaseCount(scope), "the lease released with the terminal");
        assertEquals(11L, artifacts.get(0).conversationId(),
                "the artifact is bound to the conversation for restart recovery");
    }

    @Test
    void cancelTerminalDiscardsStagingWithoutCollectingArtifacts() throws Exception {
        Fixture f = fixture();
        String scope = f.scopes().createScope(USER);
        Path target = Files.createDirectories(temp.resolve("cancel-out"));
        f.scopes().setOutputTarget(scope, USER, target.toString());
        ChatFileGrantService.StagingPreparation preparation =
                f.chatFiles().prepareStagingForTargets(List.of(target));
        ChatResourceScopeService.Lease lease = f.scopes().acquireLease(scope, USER, List.of());

        new AiController.TurnLease(f.chatFiles(), f.artifacts(), f.scopes(), scope,
                lease.leaseId(), preparation.staged()).abort();

        assertTrue(f.artifacts().listByScope(scope).isEmpty(),
                "a cancelled turn exports nothing");
        assertEquals(0, f.scopes().activeLeaseCount(scope));
    }
}
