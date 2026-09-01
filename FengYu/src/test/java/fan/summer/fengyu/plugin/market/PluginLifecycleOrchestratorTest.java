package fan.summer.fengyu.plugin.market;

import fan.summer.fengyu.plugin.runtime.PluginLogStore;
import fan.summer.fengyu.plugin.runtime.PluginProcessManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Update-gate regression for the shared plugin lifecycle orchestrator (review
 * M-3): every entry point — local upload, unified store, cloud store — gets
 * the identical begin → install → preflight → commit / stop → rollback →
 * re-preflight sequencing.
 */
class PluginLifecycleOrchestratorTest {

    PluginPackageService packages;
    PluginProcessManager processes;
    PluginLogStore logs;
    PluginLifecycleOrchestrator orchestrator;
    PluginManifest manifest;

    @BeforeEach
    void setUp() {
        packages = mock(PluginPackageService.class);
        processes = mock(PluginProcessManager.class);
        logs = mock(PluginLogStore.class);
        orchestrator = new PluginLifecycleOrchestrator(packages, processes, logs);
        manifest = mock(PluginManifest.class);
    }

    @Test
    void freshInstallSkipsPreflightAndCommit() throws Exception {
        when(packages.find("com.example.demo")).thenReturn(Optional.empty());

        PluginManifest installed = orchestrator.installWithUpdateGate("com.example.demo",
                () -> manifest);

        assertSame(manifest, installed);
        var inOrder = inOrder(processes);
        inOrder.verify(processes).beginUpdate("com.example.demo");
        inOrder.verify(processes).endUpdate("com.example.demo");
        verify(processes, never()).preflight(any());
        verify(packages, never()).commitUpdate(any());
    }

    @Test
    void updateCommitsOnlyAfterHealthyPreflight() throws Exception {
        when(packages.find("com.example.demo")).thenReturn(Optional.of(manifest));

        orchestrator.installWithUpdateGate("com.example.demo", () -> manifest);

        var inOrder = inOrder(processes, packages);
        inOrder.verify(processes).beginUpdate("com.example.demo");
        inOrder.verify(packages).commitUpdate("com.example.demo");
        inOrder.verify(processes).endUpdate("com.example.demo");
        verify(processes).preflight("com.example.demo");
        verify(processes, never()).stop(any());
        verify(packages, never()).rollbackUpdate(any());
    }

    @Test
    void unhealthyUpdateRollsBackAndRepreflightsTheRestoredVersion() throws Exception {
        when(packages.find("com.example.demo")).thenReturn(Optional.of(manifest));
        doThrow(new IllegalStateException("worker never handshook")) // first: new version
                .doNothing()                                          // then: restored old version
                .when(processes).preflight("com.example.demo");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> orchestrator.installWithUpdateGate("com.example.demo", () -> manifest));

        assertEquals("worker never handshook", error.getMessage());
        var inOrder = inOrder(processes, packages);
        inOrder.verify(processes).beginUpdate("com.example.demo");
        inOrder.verify(processes).stop("com.example.demo");
        inOrder.verify(packages).rollbackUpdate("com.example.demo");
        inOrder.verify(processes).preflight("com.example.demo"); // re-preflight restored old
        inOrder.verify(processes).endUpdate("com.example.demo");
        verify(packages, never()).commitUpdate(any());
    }

    @Test
    void rollbackPreflightFailureIsSuppressedNotSwallowed() throws Exception {
        when(packages.find("com.example.demo")).thenReturn(Optional.of(manifest));
        doThrow(new IllegalStateException("unhealthy"))
                .doThrow(new IllegalStateException("old version also unhealthy"))
                .when(processes).preflight("com.example.demo");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> orchestrator.installWithUpdateGate("com.example.demo", () -> manifest));

        assertEquals("unhealthy", error.getMessage());
        assertEquals(1, error.getSuppressed().length,
                "the rollback's own preflight failure rides along as suppressed");
        verify(processes).endUpdate("com.example.demo");
    }

    @Test
    void uninstallRunsInsideTheGateAndClearsLogs() throws Exception {
        orchestrator.uninstallWithGate("com.example.demo", true);

        var inOrder = inOrder(processes, packages, logs);
        inOrder.verify(processes).beginUpdate("com.example.demo");
        inOrder.verify(packages).uninstall("com.example.demo", true);
        inOrder.verify(processes).endUpdate("com.example.demo");
        inOrder.verify(logs).clear("com.example.demo");
    }

    @Test
    void stagedCommitReleasesTheRollbackSnapshot() throws Exception {
        orchestrator.commitStaged("com.example.demo");

        var inOrder = inOrder(packages, processes);
        inOrder.verify(packages).commitUpdate("com.example.demo");
        inOrder.verify(processes).endUpdate("com.example.demo");
    }

    @Test
    void stagedRollbackToleratesNothingToRestore() throws Exception {
        doThrow(new IOException("no .rollback snapshot"))
                .when(packages).rollbackUpdate("com.example.demo");

        assertDoesNotThrow(() -> orchestrator.rollbackStaged("com.example.demo"));

        var inOrder = inOrder(processes, packages);
        inOrder.verify(processes).stop("com.example.demo");
        inOrder.verify(packages).rollbackUpdate("com.example.demo");
        inOrder.verify(processes).preflight("com.example.demo");
        inOrder.verify(processes).endUpdate("com.example.demo");
    }

    @Test
    void unpreviewableIdInstallsWithoutAGate() throws Exception {
        PluginManifest installed = orchestrator.installWithUpdateGate(null, () -> manifest);

        assertSame(manifest, installed);
        verifyNoInteractions(processes);
        verify(packages, never()).find(any());
    }
}
