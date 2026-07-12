package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.plugin.PluginRegistryService;
import fan.summer.fengyu.plugin.workspace.PluginWorkspaceService;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PluginFileControllerTest {

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        PluginRegistryService registry = mock(PluginRegistryService.class);
        when(registry.find("fan.summer.excel")).thenReturn(java.util.Optional.of(mock(fan.summer.fengyu.api.plugin.FengYuPlugin.class)));
        mvc = MockMvcBuilders.standaloneSetup(
            new PluginFileController(new PluginWorkspaceService(), registry)).build();
    }

    @Test
    void uploadReturnsSessionAndPath() throws Exception {
        MockMultipartFile f = new MockMultipartFile("file", "in.xlsx",
            "application/octet-stream", "x".getBytes());
        mvc.perform(multipart("/api/plugins/fan.summer.excel/files").file(f))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.session").isNotEmpty())
           .andExpect(jsonPath("$.files[0].name").value("in.xlsx"))
           .andExpect(jsonPath("$.files[0].path").isNotEmpty());
    }

    @Test
    void uploadUnknownPluginIs404() throws Exception {
        MockMultipartFile f = new MockMultipartFile("file", "in.xlsx", null, "x".getBytes());
        mvc.perform(multipart("/api/plugins/does.not.exist/files").file(f))
           .andExpect(status().isNotFound());
    }

    @Test
    void rejectsBadExtension() throws Exception {
        MockMultipartFile f = new MockMultipartFile("file", "in.exe", null, "x".getBytes());
        mvc.perform(multipart("/api/plugins/fan.summer.excel/files").file(f))
           .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsFileOver100MB() throws Exception {
        // Reports a size just over the controller's 100MB business cap without allocating
        // 100MB of heap: MockMvc's standalone setup does not enforce servlet multipart limits,
        // so this exercises PluginFileController's own `file.getSize() > MAX_BYTES` check.
        MockMultipartFile f = new MockMultipartFile("file", "in.xlsx",
            "application/octet-stream", "x".getBytes()) {
            @Override
            public long getSize() {
                return 101L * 1024 * 1024;
            }
        };
        mvc.perform(multipart("/api/plugins/fan.summer.excel/files").file(f))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.success").value(false));
    }
}
