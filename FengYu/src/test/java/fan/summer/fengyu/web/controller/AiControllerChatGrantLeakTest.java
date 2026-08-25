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
}
