package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.ChatFileContext;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService.FileRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiControllerFileContextTest {

    @Autowired MockMvc mvc;

    @AfterEach
    void clean() { ChatFileContext.clear(); }

    @Test
    void acceptsActiveFileRefsFieldWithoutError() throws Exception {
        // POST /api/ai/chat must accept the new activeFileRefs field. We only assert the endpoint
        // accepts the body and returns a streamId; resolving the SSE is out of scope here.
        String body = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
            + "\"activeFileRefs\":[{\"pluginId\":\"fan.summer.excel\","
            + "\"ref\":{\"id\":\"ref_3f2a\",\"name\":\"report.xlsx\",\"kind\":\"file\",\"access\":\"read\",\"size\":123}}]}";

        mvc.perform(post("/api/ai/chat").contentType("application/json").content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.streamId").exists());
    }

    @Test
    void chatRequestRecordExposesActiveFileRefs() {
        // Pins the DTO shape later tasks/future readers rely on.
        var ref = new FileRef("ref_1", "f", "file", "read", 1L);
        var req = new AiController.ChatRequest(
            java.util.List.of(new AiController.ChatMessageDto("user", "hi")),
            java.util.List.of(new AiController.ActiveFileRefDto("fan.summer.excel", ref)));
        org.junit.jupiter.api.Assertions.assertEquals(1, req.activeFileRefs().size());
        org.junit.jupiter.api.Assertions.assertEquals("fan.summer.excel", req.activeFileRefs().get(0).pluginId());
    }
}
