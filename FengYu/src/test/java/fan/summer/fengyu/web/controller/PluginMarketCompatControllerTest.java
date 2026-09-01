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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Compatibility-surface regression (review R-3 / design §10.3): the legacy
 * {@code /api/plugin-market} lifecycle endpoints keep working through the same
 * gated sequencing as {@code /api/plugin-packages} — with deprecation headers —
 * and the endpoints superseded by the unified store answer 410 Gone naming
 * their replacement.
 */
class PluginMarketCompatControllerTest {

    private PluginPackageService packages;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        packages = mock(PluginPackageService.class);
        PluginProcessManager processes = mock(PluginProcessManager.class);
        PluginLogStore logs = mock(PluginLogStore.class);
        mvc = MockMvcBuilders.standaloneSetup(new PluginMarketCompatController(
                packages, new PluginLifecycleOrchestrator(packages, processes, logs)))
                .build();
    }

    private static PluginManifest manifest(String id) {
        return new PluginManifest(2, id, id, "d", "1.0.0", "a", "i", "c", null,
                null, java.util.List.of(), null, false, null, null, null, null);
    }

    @Test
    void uploadAliasRunsTheGatedLifecycleWithDeprecationHeaders() throws Exception {
        when(packages.readArchiveManifest(any(MockMultipartFile.class)))
                .thenReturn(manifest("com.example.demo"));
        when(packages.find("com.example.demo")).thenReturn(Optional.empty());
        when(packages.install(any(org.springframework.web.multipart.MultipartFile.class),
                any(), anyBoolean())).thenReturn(manifest("com.example.demo"));

        mvc.perform(multipart("/api/plugin-market/upload")
                        .file(new MockMultipartFile("file", "demo.fyp",
                                "application/octet-stream", new byte[] {1})))
                .andExpect(status().isCreated())
                .andExpect(header().string("Deprecation",
                        org.hamcrest.Matchers.containsString("4.0.0-rc.1")))
                .andExpect(header().string("Link",
                        org.hamcrest.Matchers.containsString("/api/plugin-packages")))
                .andExpect(jsonPath("$.id").value("com.example.demo"));
    }

    @Test
    void uninstallAliasUsesTheGate() throws Exception {
        mvc.perform(delete("/api/plugin-market/com.example.demo")
                        .param("deleteData", "false"))
                .andExpect(status().isNoContent());

        verify(packages).uninstall("com.example.demo", false);
    }

    @Test
    void removedCatalogEndpointsAnswer410NamingTheirReplacement() throws Exception {
        mvc.perform(get("/api/plugin-market"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.replacement").value("/api/plugin-store/catalog"));

        mvc.perform(post("/api/plugin-market/com.example.demo/install"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.replacement")
                        .value("/api/plugin-store/{uid}/install"));

        mvc.perform(post("/api/plugin-market/com.example.demo/update"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.replacement")
                        .value("/api/plugin-store/{uid}/update"));
    }
}
