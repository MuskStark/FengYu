package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.ChatFileGrantService;
import fan.summer.fengyu.ai.config.AiToolRegistry;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M4 regression: an invalid workflowId in POST /api/ai/chat must fail the request BEFORE the
 * composer-path grants and write-target staging directories are minted. Under the old ordering
 * the boundTools validation ran after grantPathsFromUserText/prepareStagingForWriteTargets, so
 * every rejected request leaked its grants (accumulating toward the active-grant cap) plus an
 * orphaned staging directory.
 */
class AiControllerChatGrantLeakTest {

    private static final String PLUGIN_ID = "test.writer";

    @TempDir Path temp;

    @SuppressWarnings("unchecked")
    private AiController controller(PluginFileGrantService files, AiToolRegistry registry) throws Exception {
        Path pluginRoot = Files.createDirectories(temp.resolve("plugins"));
        Path pluginDir = Files.createDirectories(pluginRoot.resolve(PLUGIN_ID));
        Files.writeString(pluginDir.resolve("manifest.json"), """
            {"schemaVersion":2,"id":"%s","name":"Writer","description":"test","version":"1.0.0",
             "author":"test","icon":"test","category":"OTHER","ui":{"entry":"ui/index.html"},
             "backend":{"callTimeoutSeconds":60},"permissions":["files.read","files.write"],
             "official":false,"aiTools":[]}
            """.formatted(PLUGIN_ID));
        ChatFileGrantService chatFiles = new ChatFileGrantService(
                new PluginPackageService(pluginRoot.toString()), files);
        ObjectProvider<AiToolRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return new AiController(mock(AiModeService.class), new ChatToolApprovalGate(),
                chatFiles, files, new StreamTicketService(), provider);
    }

    /** Control: the same composer text DOES mint grants when the request is otherwise valid. */
    @Test
    void composerPathMintsGrantsWhenTheRequestIsValid() throws Exception {
        PluginFileGrantService files = new PluginFileGrantService(temp.resolve("grants-ok").toString());
        AiToolRegistry registry = Mockito.mock(AiToolRegistry.class);
        AiController controller = controller(files, registry);
        Path doc = Files.writeString(temp.resolve("notes.txt"), "data");

        controller.chat(new AiController.ChatRequest(
                List.of(new AiController.ChatMessageDto("user", "read " + doc)), null),
                null);

        assertEquals(1, files.readablePaths(PLUGIN_ID).size(),
                "control: an existing absolute path in the latest user message mints a grant");
    }

    /** An invalid workflowId must reject the turn with no grant or staging side effects. */
    @Test
    void invalidWorkflowIdLeavesNoGrantsOrStagingBehind() throws Exception {
        PluginFileGrantService files = new PluginFileGrantService(temp.resolve("grants-bad").toString());
        AiToolRegistry registry = Mockito.mock(AiToolRegistry.class);
        when(registry.boundWorkflowTool("wf-missing"))
                .thenThrow(new IllegalArgumentException("Unknown workflow: wf-missing"));
        AiController controller = controller(files, registry);
        Path doc = Files.writeString(temp.resolve("notes-bad.txt"), "data");

        var rejected = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.chat(new AiController.ChatRequest(
                        List.of(new AiController.ChatMessageDto("user", "read " + doc)),
                        null, null, "wf-missing"), null));

        assertEquals(400, rejected.getStatusCode().value());
        assertEquals(0, files.readablePaths(PLUGIN_ID).size(),
                "a rejected chat turn must leave no grants or staging grants behind");
        assertTrue(Files.notExists(temp.resolve("grants-bad").resolve(PLUGIN_ID)),
                "no staging directory may be created for a rejected turn");
    }

    @Test
    void dirtyFlowContextBindsAuthoringToolsButNotSavedFlowExecution() throws Exception {
        PluginFileGrantService files = new PluginFileGrantService(temp.resolve("grants-authoring").toString());
        AiToolRegistry registry = Mockito.mock(AiToolRegistry.class);
        when(registry.boundFlowAuthoringTools(Mockito.anyMap(), Mockito.any()))
                .thenReturn(List.of());
        AiController controller = controller(files, registry);
        Map<String, Object> context = Map.of(
                "workflowId", "wf-draft",
                "dirty", true,
                "snapshotId", "snapshot-1",
                "graph", Map.of("nodes", List.of(), "edges", List.of()));

        controller.chat(new AiController.ChatRequest(
                List.of(new AiController.ChatMessageDto("user", "diagnose this flow")),
                null, null, "wf-draft", context), null);

        verify(registry).boundFlowAuthoringTools(Mockito.eq(context), Mockito.any());
        verify(registry, never()).boundWorkflowTool(Mockito.anyString());
    }

    /**
     * The output-directory scenario: staging grants are strictly turn-scoped (revoked at the
     * turn's terminal), so they must never ride the POST response — a client that stored and
     * resent one would have its whole next request rejected at validate().
     */
    @Test
    @SuppressWarnings("unchecked")
    void writeTargetStagingIsNeverEchoedToTheClient() throws Exception {
        PluginFileGrantService files = new PluginFileGrantService(temp.resolve("grants-staging").toString());
        AiController controller = controller(files, Mockito.mock(AiToolRegistry.class));
        Path outDir = Files.createDirectories(temp.resolve("out-target"));

        Map<String, Object> response = controller.chat(new AiController.ChatRequest(
                List.of(new AiController.ChatMessageDto("user", "output the split files to " + outDir)), null),
                null);

        assertFalse(files.writablePaths(PLUGIN_ID).isEmpty(),
                "control: staging was minted for the write target");
        List<AiController.ActiveFileRefDto> echoed =
                (List<AiController.ActiveFileRefDto>) response.get("activeFileRefs");
        for (AiController.ActiveFileRefDto dto : echoed) {
            Path granted = files.resolve(dto.pluginId(), dto.ref().id());
            assertFalse(files.writablePaths(PLUGIN_ID).contains(granted),
                    "a turn-scoped staging grant must never be handed to the client");
        }
    }

    /** Hand-over boundary: refs echoed with the response stay valid for the follow-up turn. */
    @Test
    @SuppressWarnings("unchecked")
    void responseRefsRemainValidForFollowUpTurns() throws Exception {
        PluginFileGrantService files = new PluginFileGrantService(temp.resolve("grants-followup").toString());
        AiController controller = controller(files, Mockito.mock(AiToolRegistry.class));
        Path doc = Files.writeString(temp.resolve("followup.txt"), "data");

        Map<String, Object> first = controller.chat(new AiController.ChatRequest(
                List.of(new AiController.ChatMessageDto("user", "read " + doc)), null), null);
        List<AiController.ActiveFileRefDto> handed =
                (List<AiController.ActiveFileRefDto>) first.get("activeFileRefs");
        assertFalse(handed.isEmpty(), "control: the composer path minted a persistent grant");

        // The client's "continue" turn resends exactly what it was handed — validate must accept.
        controller.chat(new AiController.ChatRequest(
                List.of(new AiController.ChatMessageDto("user", "continue")),
                handed.stream().map(dto -> new AiController.ActiveFileRefDto(dto.pluginId(), dto.ref()))
                        .toList()), null);
    }

    /**
     * Sweeping an abandoned (never-streamed) turn reclaims its staging but never the caller's
     * attachments nor the persistent refs already handed over with the POST response.
     */
    @Test
    @SuppressWarnings("unchecked")
    void sweepReclaimsStagingButNotClientAttachmentsOrPersistentRefs() throws Exception {
        PluginFileGrantService files = new PluginFileGrantService(temp.resolve("grants-sweep").toString());
        AiController controller = controller(files, Mockito.mock(AiToolRegistry.class));
        Path doc = Files.writeString(temp.resolve("sweep.txt"), "data");
        Path outDir = Files.createDirectories(temp.resolve("sweep-out"));
        PluginFileGrantService.FileRef attachment =
                files.grantNative(PLUGIN_ID, doc.toString(), "file", "read");

        Map<String, Object> response = controller.chat(new AiController.ChatRequest(
                List.of(new AiController.ChatMessageDto("user", "output the files to " + outDir)),
                List.of(new AiController.ActiveFileRefDto(PLUGIN_ID, attachment))), null);
        List<AiController.ActiveFileRefDto> handed =
                (List<AiController.ActiveFileRefDto>) response.get("activeFileRefs");
        assertFalse(handed.isEmpty(), "control: the read grant on the target dir was handed over");
        assertFalse(files.writablePaths(PLUGIN_ID).isEmpty(), "control: staging exists pre-sweep");

        controller.sweepExpiredPendingTurns(java.time.Instant.now().plusSeconds(1));

        assertTrue(files.writablePaths(PLUGIN_ID).isEmpty(), "the abandoned turn's staging is reclaimed");
        files.validate(PLUGIN_ID, attachment);
        for (AiController.ActiveFileRefDto dto : handed) files.validate(dto.pluginId(), dto.ref());
    }

    /**
     * A failure while minting this turn's grants must revoke only what this request minted —
     * the caller's pre-existing attachments stay untouched.
     */
    @Test
    void mintingFailureRevokesOnlyNewlyMintedGrants() throws Exception {
        PluginFileGrantService files = new PluginFileGrantService(temp.resolve("grants-mint-fail").toString());
        AiController controller = controller(files, Mockito.mock(AiToolRegistry.class));
        Path okDoc = Files.writeString(temp.resolve("ok.txt"), "data");
        Path big = temp.resolve("big.bin");
        // Sparse 101 MB file: trips enforceNativeQuota after the first path already minted a grant.
        try (var channel = java.nio.channels.FileChannel.open(big,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(101L * 1024 * 1024);
            channel.write(java.nio.ByteBuffer.wrap(new byte[] { 1 }));
        }
        PluginFileGrantService.FileRef attachment =
                files.grantNative(PLUGIN_ID, okDoc.toString(), "file", "read");

        assertThrows(IllegalArgumentException.class, () -> controller.chat(new AiController.ChatRequest(
                List.of(new AiController.ChatMessageDto("user",
                        "read " + okDoc + " and " + big)),
                List.of(new AiController.ActiveFileRefDto(PLUGIN_ID, attachment))), null));

        assertEquals(1, files.readablePaths(PLUGIN_ID).size(),
                "only the caller's own attachment may survive a minting failure");
        files.validate(PLUGIN_ID, attachment);
    }

    /**
     * A stream that fails BEFORE the backend starts (here: no AI backend configured) must
     * discard its staging — the turn is already out of `pending`, so no sweep would reclaim it.
     */
    @Test
    void streamThatNeverStartsDiscardsItsStaging() throws Exception {
        PluginFileGrantService files = new PluginFileGrantService(temp.resolve("grants-nostart").toString());
        AiController controller = controller(files, Mockito.mock(AiToolRegistry.class));
        Path outDir = Files.createDirectories(temp.resolve("nostart-out"));

        Map<String, Object> started = controller.chat(new AiController.ChatRequest(
                List.of(new AiController.ChatMessageDto("user", "output the files to " + outDir)), null),
                null);
        assertFalse(files.writablePaths(PLUGIN_ID).isEmpty(), "control: staging exists after POST");

        controller.stream(String.valueOf(started.get("streamId")));

        assertTrue(files.writablePaths(PLUGIN_ID).isEmpty(),
                "a stream that never starts must discard its turn-scoped staging");
    }
}
