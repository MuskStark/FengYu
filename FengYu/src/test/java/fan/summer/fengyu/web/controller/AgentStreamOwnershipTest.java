package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.agent.AgentRun;
import fan.summer.fengyu.ai.agent.AgentRunConfig;
import fan.summer.fengyu.ai.agent.AgentRunRegistry;
import fan.summer.fengyu.ai.agent.AgentRunner;
import fan.summer.fengyu.ai.agent.AgentRunPersistenceService;
import fan.summer.fengyu.ai.config.AiToolRegistry;
import fan.summer.fengyu.ai.workflow.WorkflowExecutionService;
import fan.summer.fengyu.ai.workflow.WorkflowService;
import fan.summer.fengyu.security.SecurityContext;
import fan.summer.fengyu.web.StreamTicketService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1-4: {@code GET /api/agent/stream?runId=} resolves the sink by bare id, so it must verify
 * ownership through {@link AgentRunRegistry#get} (exactly like approve/cancel) before any
 * sink is handed out — otherwise a runId alone would subscribe another user's run stream
 * (plan tokens, step arguments, tool results). Unknown, expired, and foreign runIds share
 * one message so the endpoint is not an existence oracle either.
 */
class AgentStreamOwnershipTest {

    private final AtomicLong currentUser = new AtomicLong(1L);
    private final AgentRunRegistry registry;

    AgentStreamOwnershipTest() {
        SecurityContext security = mock(SecurityContext.class);
        org.mockito.Mockito.when(security.currentUserId())
                .thenAnswer(invocation -> currentUser.get());
        registry = spy(new AgentRunRegistry(security));
    }

    private MockMvc mockMvc() {
        return org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new AgentController(
                        mock(AgentRunner.class), registry,
                        mock(AgentRunPersistenceService.class), mock(AiToolRegistry.class),
                        mock(WorkflowService.class), mock(WorkflowExecutionService.class),
                        new StreamTicketService(),
                        mock(fan.summer.fengyu.ai.ChatFileGrantService.class),
                        mock(fan.summer.fengyu.plugin.runtime.PluginFileGrantService.class)))
                .build();
    }

    @Test
    void streamResolvesOwnershipThroughTheRegistryBeforeAnySinkLookup() throws Exception {
        // User 1 owns the run; user 2 asks for its stream.
        AgentRun owned = registry.create("secret plan", new AgentRunConfig(false, false, false, 0));
        assertNotNull(registry.get(owned.getRunId()), "owner resolves the run");

        currentUser.set(2L);
        assertNull(registry.get(owned.getRunId()), "a foreign user must not resolve the run");

        MvcResult result = mockMvc().perform(get("/api/agent/stream")
                        .param("runId", owned.getRunId())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mockMvc().perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The controller must have consulted the ownership-checked lookup — the same one
        // approve/cancel use — and refused with the terminal error before touching a sink.
        verify(registry, org.mockito.Mockito.atLeastOnce()).get(owned.getRunId());
        assertTrue(body.contains("event:error"), "must be a terminal error event: " + body);
        assertTrue(body.contains("Unknown or expired runId"),
                "the foreign request must be refused: " + body);
    }
}
