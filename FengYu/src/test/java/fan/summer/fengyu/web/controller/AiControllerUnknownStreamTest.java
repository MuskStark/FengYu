package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.ChatFileGrantService;
import fan.summer.fengyu.ai.service.AiModeService;
import fan.summer.fengyu.ai.tools.ChatToolApprovalGate;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService;
import fan.summer.fengyu.web.StreamTicketService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CQ-02(b): the first connection consumes the pending entry and a transport disconnect
 * cancels generation, so reconnecting with the SAME streamId can never succeed — the
 * {@code error} event for an unknown/expired streamId must be terminal and carry the
 * machine-readable {@code "code":"unknown_stream"} so the client stops blind-retrying.
 */
class AiControllerUnknownStreamTest {

    private final MockMvc mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
            .standaloneSetup(new AiController(
                    mock(AiModeService.class), new ChatToolApprovalGate(),
                    mock(ChatFileGrantService.class), mock(PluginFileGrantService.class),
                    new StreamTicketService(), null))
            .build();

    @Test
    void unknownStreamIdEmitsTerminalErrorWithMachineCode() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/ai/stream")
                        .param("streamId", "never-issued")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("event:error"), "must be a named error event: " + body);
        assertTrue(body.contains("\"code\":\"unknown_stream\""),
                "payload must carry the machine code unknown_stream: " + body);
        assertTrue(body.contains("Unknown or expired streamId"),
                "payload must keep the human message: " + body);
    }
}
