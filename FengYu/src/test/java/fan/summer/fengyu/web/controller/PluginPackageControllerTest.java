package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.plugin.market.PluginLifecycleOrchestrator;
import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.runtime.PluginLogStore;
import fan.summer.fengyu.plugin.runtime.PluginProcessManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP regression for the local {@code .fyp} lifecycle API (review R-3): the
 * controller routes every install and uninstall through the shared
 * {@link PluginLifecycleOrchestrator} gate — worker stop, health preflight,
 * commit/rollback — instead of sequencing transactions itself.
 */
class PluginPackageControllerTest {

    private PluginPackageService packages;
    private PluginProcessManager processes;
    private PluginLogStore logs;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        packages = mock(PluginPackageService.class);
        processes = mock(PluginProcessManager.class);
        logs = mock(PluginLogStore.class);
        // The REAL orchestrator over mocked runtime pieces: the test pins the
        // gate sequencing, not a mock of it.
        mvc = MockMvcBuilders.standaloneSetup(new PluginPackageController(packages,
                new PluginLifecycleOrchestrator(packages, processes, logs))).build();
    }

    private static PluginManifest manifest(String id) {
        return new PluginManifest(2, id, id, "d", "1.0.0", "a", "i", "c", null,
                null, java.util.List.of(), null, false, null, null, null, null);
    }

    @Test
    void uploadRunsInsideTheUpdateGate() throws Exception {
        when(packages.readArchiveManifest(any(MockMultipartFile.class)))
                .thenReturn(manifest("com.example.demo"));
        when(packages.find("com.example.demo")).thenReturn(Optional.empty());
        when(packages.install(any(org.springframework.web.multipart.MultipartFile.class),
                any(), anyBoolean())).thenReturn(manifest("com.example.demo"));

        mvc.perform(multipart("/api/plugin-packages/upload")
                        .file(new MockMultipartFile("file", "demo.fyp",
                                "application/octet-stream", new byte[] {1, 2, 3})))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("com.example.demo"));

        verify(processes).beginUpdate("com.example.demo");
        verify(processes).endUpdate("com.example.demo");
        // Fresh install: no previous version to preflight or commit.
        verify(processes, never()).preflight(any());
        verify(packages, never()).commitUpdate(any());
    }

    @Test
    void uploadOfAnUpdateCommitsOnlyAfterHealthyPreflight() throws Exception {
        when(packages.readArchiveManifest(any(MockMultipartFile.class)))
                .thenReturn(manifest("com.example.demo"));
        when(packages.find("com.example.demo"))
                .thenReturn(Optional.of(manifest("com.example.demo")));
        when(packages.install(any(org.springframework.web.multipart.MultipartFile.class),
                any(), anyBoolean())).thenReturn(manifest("com.example.demo"));

        mvc.perform(multipart("/api/plugin-packages/upload")
                        .file(new MockMultipartFile("file", "demo.fyp",
                                "application/octet-stream", new byte[] {1, 2, 3})))
                .andExpect(status().isCreated());

        verify(processes).preflight("com.example.demo");
        verify(packages).commitUpdate("com.example.demo");
        verify(packages, never()).rollbackUpdate(any());
    }

    @Test
    void unhealthyUpdateIsRolledBack() throws Exception {
        when(packages.readArchiveManifest(any(MockMultipartFile.class)))
                .thenReturn(manifest("com.example.demo"));
        when(packages.find("com.example.demo"))
                .thenReturn(Optional.of(manifest("com.example.demo")));
        when(packages.install(any(org.springframework.web.multipart.MultipartFile.class),
                any(), anyBoolean())).thenReturn(manifest("com.example.demo"));
        org.mockito.Mockito.doThrow(new IllegalStateException("unhealthy"))
                .when(processes).preflight("com.example.demo");

        // Standalone MockMvc has no advice mapping IllegalStateException → the
        // failure surfaces as a servlet exception; the rollback is the contract.
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> mvc.perform(multipart("/api/plugin-packages/upload")
                        .file(new MockMultipartFile("file", "demo.fyp",
                                "application/octet-stream", new byte[] {1, 2, 3}))));

        verify(processes).stop("com.example.demo");
        verify(packages).rollbackUpdate("com.example.demo");
        verify(packages, never()).commitUpdate(any());
    }

    @Test
    void unpreviewableUploadInstallsWithoutAGate() throws Exception {
        when(packages.readArchiveManifest(any(MockMultipartFile.class)))
                .thenThrow(new java.io.IOException("broken archive"));
        when(packages.install(any(org.springframework.web.multipart.MultipartFile.class),
                any(), anyBoolean())).thenReturn(manifest("com.example.fresh"));

        mvc.perform(multipart("/api/plugin-packages/upload")
                        .file(new MockMultipartFile("file", "demo.fyp",
                                "application/octet-stream", new byte[] {1})))
                .andExpect(status().isCreated());

        verify(processes, never()).beginUpdate(any());
    }

    @Test
    void disableStopsTheWorker() throws Exception {
        mvc.perform(patch("/api/plugin-packages/com.example.demo/enabled")
                        .contentType("application/json")
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        verify(packages).setEnabled("com.example.demo", false);
        verify(processes).stop("com.example.demo");
    }

    @Test
    void uninstallUsesTheGateAndClearsLogs() throws Exception {
        mvc.perform(delete("/api/plugin-packages/com.example.demo")
                        .param("deleteData", "true"))
                .andExpect(status().isNoContent());

        verify(processes).beginUpdate("com.example.demo");
        verify(packages).uninstall("com.example.demo", true);
        verify(processes).endUpdate("com.example.demo");
        verify(logs).clear("com.example.demo");
    }
}
