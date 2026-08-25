package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.ChatFileGrantService;
import fan.summer.fengyu.ai.agent.AgentRunner;
import fan.summer.fengyu.ai.agent.AgentRunPersistenceService;
import fan.summer.fengyu.ai.agent.AgentRunRegistry;
import fan.summer.fengyu.ai.workflow.WorkflowExecutionService;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService;
import fan.summer.fengyu.security.NoopSecurityContext;
import fan.summer.fengyu.web.StreamTicketService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;

/**
 * M2 regression: a file-bearing run mints plugin file grants (native snapshots / shared scratch
 * dirs) that only THIS controller can revoke — nothing else owns them. The run endpoints must
 * therefore track what they issued and revoke it on the terminal cleanup (and on every failure
 * path before the cleanup is registered), or repeated file-bearing runs accumulate grants until
 * PluginFileGrantService's MAX_ACTIVE_GRANTS cap rejects every new file input.
 */
class AgentControllerRunFileGrantTest {

    private static final String PLUGIN_ID = "test.reader";

    @TempDir Path temp;

    /** One installed files.read plugin, its grant service, and a controller wired to both. */
    private record Fixture(AgentController controller, PluginFileGrantService files) {}

    private Fixture fixture(String tag) throws Exception {
        return fixture(tag, mock(AgentRunPersistenceService.class),
                mock(WorkflowExecutionService.class));
    }

    private Fixture fixture(String tag, AgentRunPersistenceService persistence,
            WorkflowExecutionService execution) throws Exception {
        Path pluginRoot = Files.createDirectories(temp.resolve("plugins-" + tag));
        Path pluginDir = Files.createDirectories(pluginRoot.resolve(PLUGIN_ID));
        Files.writeString(pluginDir.resolve("manifest.json"), manifest());
        PluginFileGrantService files = new PluginFileGrantService(temp.resolve("grants-" + tag).toString());
        ChatFileGrantService chatFiles = new ChatFileGrantService(
                new PluginPackageService(pluginRoot.toString()), files);
        AgentController controller = new AgentController(
                mock(AgentRunner.class), new AgentRunRegistry(new NoopSecurityContext()),
                persistence, null, null, execution,
                new StreamTicketService(), chatFiles, files);
        return new Fixture(controller, files);
    }

    private static AgentController.AgentRunRequest requestWithNativeFile(Path file) {
        return new AgentController.AgentRunRequest("goal", null, null, List.of(
                new AgentController.RunFile("src", null, file.toString(), "file", false, false)));
    }

    /** Repeated file-bearing runs whose terminal cleanup revokes must never exhaust the grant cap. */
    @Test
    void terminalCleanupKeepsActiveGrantsBounded() throws Exception {
        Fixture fixture = fixture("bounded");
        Path input = Files.writeString(temp.resolve("input.txt"), "data");

        // Far more runs than MAX_ACTIVE_GRANTS (1000): with per-run revocation the grant pool
        // returns to empty after every run instead of monotonically filling toward the cap.
        for (int i = 0; i < 1_100; i++) {
            Map<String, String> started = fixture.controller().run(requestWithNativeFile(input));
            // What scheduleCleanup does once the run's retention window elapses.
            fixture.controller().revokeRunFileGrants(started.get("runId"));
        }
        assertEquals(0, fixture.files().readablePaths(PLUGIN_ID).size(),
                "terminal cleanup must return every issued grant to the pool");
    }

    /** Control: without the cleanup the service's cap really does trip — proving the loop above
     *  exercises the cap rather than a no-op path. */
    @Test
    void withoutCleanupRunGrantsExhaustTheCap() throws Exception {
        Fixture fixture = fixture("uncleaned");
        AgentController controller = fixture.controller();
        Path input = Files.writeString(temp.resolve("input-uncleaned.txt"), "data");

        List<String> runIds = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            runIds.add(controller.run(requestWithNativeFile(input)).get("runId"));
        }
        var capped = assertThrows(IllegalStateException.class,
                () -> controller.run(requestWithNativeFile(input)));
        assertTrue(capped.getMessage().contains("Too many active file grants"),
                "expected the active-grant cap to trip: " + capped.getMessage());
        // Late cleanups (every terminal run still eventually revokes) free the pool again.
        for (String runId : runIds) controller.revokeRunFileGrants(runId);
        assertEquals(0, fixture.files().readablePaths(PLUGIN_ID).size());
        controller.run(requestWithNativeFile(input));  // must succeed again — no exception
    }

    /** A failure between grant issuance and run start (here: persistence throws) must revoke. */
    @Test
    void failedRunStartDoesNotLeakIssuedGrants() throws Exception {
        Path input = Files.writeString(temp.resolve("input-failed.txt"), "data");
        AgentRunPersistenceService failing = mock(AgentRunPersistenceService.class);
        Mockito.doThrow(new IllegalStateException("db down")).when(failing).create(any(), any());
        Fixture fixture = fixture("failed-start", failing, mock(WorkflowExecutionService.class));

        assertThrows(IllegalStateException.class,
                () -> fixture.controller().run(requestWithNativeFile(input)));
        assertEquals(0, fixture.files().readablePaths(PLUGIN_ID).size(),
                "a run that never started must leave no issued grants behind");
    }

    /** The second file input failing must revoke what the first input already minted. */
    @Test
    void partiallyResolvedRunFilesRevokedOnFailure() throws Exception {
        Fixture fixture = fixture("partial");
        Path input = Files.writeString(temp.resolve("input-partial.txt"), "data");
        // First input valid (mints a grant), second input's name is invalid → whole call throws.
        var request = new AgentController.AgentRunRequest("goal", null, null, List.of(
                new AgentController.RunFile("src", null, input.toString(), "file", false, false),
                new AgentController.RunFile("bad name!", null, null, null, false, false)));

        assertThrows(IllegalArgumentException.class, () -> fixture.controller().run(request));
        assertEquals(0, fixture.files().readablePaths(PLUGIN_ID).size(),
                "grants minted by earlier inputs must be revoked when a later input fails");
    }

    /** runWorkflow failing after resolveRunFiles (bad workflowId / compile error) must revoke. */
    @Test
    void failedWorkflowCreationDoesNotLeakIssuedGrants() throws Exception {
        WorkflowExecutionService execution = mock(WorkflowExecutionService.class);
        Mockito.when(execution.createManual(anyString(), anyMap(), any(), anyMap()))
                .thenThrow(new IllegalArgumentException("Unknown workflow: wf-missing"));
        Fixture fixture = fixture("failed-workflow",
                mock(AgentRunPersistenceService.class), execution);
        Path input = Files.writeString(temp.resolve("input-wf.txt"), "data");

        assertThrows(IllegalArgumentException.class, () -> fixture.controller().runWorkflow(
                "wf-missing", new AgentController.WorkflowRunRequest(Map.of(), null, List.of(
                        new AgentController.RunFile("src", null, input.toString(), "file", false, false)))));
        assertEquals(0, fixture.files().readablePaths(PLUGIN_ID).size(),
                "a workflow that never started must leave no issued grants behind");
    }

    /**
     * Picker/upload refs are ADOPTED, not passed through: once the run exists it is their single
     * owner, and its terminal cleanup must return them to the pool (repeat calls stay idempotent).
     */
    @Test
    void pickerRefsAreAdoptedAndRevokedAtRunTerminal() throws Exception {
        Fixture fixture = fixture("picker-terminal");
        Path picked = Files.writeString(temp.resolve("picked.txt"), "data");
        PluginFileGrantService.FileRef pickerRef =
                fixture.files().grantNative(PLUGIN_ID, picked.toString(), "file", "read");
        var request = new AgentController.AgentRunRequest("goal", null, null, List.of(
                new AgentController.RunFile("src",
                        List.of(new AiFileController.ActiveFileRefDto(PLUGIN_ID, pickerRef)),
                        null, null, false, false)));

        Map<String, String> started = fixture.controller().run(request);
        fixture.controller().revokeRunFileGrants(started.get("runId"));
        fixture.controller().revokeRunFileGrants(started.get("runId")); // idempotent by contract

        assertEquals(0, fixture.files().readablePaths(PLUGIN_ID).size(),
                "the terminal cleanup must also reclaim adopted picker grants");
        assertThrows(IllegalArgumentException.class,
                () -> fixture.files().validate(PLUGIN_ID, pickerRef),
                "the adopted grant must really be revoked, not merely unlisted");
    }

    /** A run that never starts must revoke picker, native, AND shared-directory grants together. */
    @Test
    void failedRunStartRevokesAdoptedPickerNativeAndSharedGrants() throws Exception {
        Path picked = Files.writeString(temp.resolve("picked-mixed.txt"), "data");
        AgentRunPersistenceService failing = mock(AgentRunPersistenceService.class);
        Mockito.doThrow(new IllegalStateException("db down")).when(failing).create(any(), any());
        Fixture fixture = fixture("picker-failed-start", failing, mock(WorkflowExecutionService.class));
        PluginFileGrantService.FileRef pickerRef =
                fixture.files().grantNative(PLUGIN_ID, picked.toString(), "file", "read");
        var request = new AgentController.AgentRunRequest("goal", null, null, List.of(
                new AgentController.RunFile("picked",
                        List.of(new AiFileController.ActiveFileRefDto(PLUGIN_ID, pickerRef)),
                        null, null, false, false),
                new AgentController.RunFile("native", null, picked.toString(), "file", false, false),
                new AgentController.RunFile("scratch", null, null, null, null, true)));

        assertThrows(IllegalStateException.class, () -> fixture.controller().run(request));
        assertEquals(0, fixture.files().readablePaths(PLUGIN_ID).size(),
                "an unstarted run must leave neither adopted nor minted readable grants");
        assertEquals(0, fixture.files().writablePaths(PLUGIN_ID).size(),
                "an unstarted run must reclaim its shared scratch grants");
    }

    private static String manifest() {
        return """
            {"schemaVersion":2,"id":"%s","name":"Reader","description":"test","version":"1.0.0",
             "author":"test","icon":"test","category":"OTHER","ui":{"entry":"ui/index.html"},
             "backend":{"callTimeoutSeconds":60},"permissions":["files.read"],"official":false,
             "aiTools":[]}
            """.formatted(PLUGIN_ID);
    }
}
