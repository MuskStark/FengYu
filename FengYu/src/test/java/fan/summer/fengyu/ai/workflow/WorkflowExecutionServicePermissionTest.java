package fan.summer.fengyu.ai.workflow;

import fan.summer.fengyu.ai.agent.AgentPlan;
import fan.summer.fengyu.ai.agent.AgentEventSink;
import fan.summer.fengyu.ai.agent.AgentStep;
import fan.summer.fengyu.ai.agent.AgentRun;
import fan.summer.fengyu.ai.agent.AgentRunPersistenceService;
import fan.summer.fengyu.ai.agent.AgentRunRegistry;
import fan.summer.fengyu.ai.agent.AgentRunner;
import fan.summer.fengyu.ai.tools.AiPermissionContext;
import fan.summer.fengyu.ai.tools.AiPermissionMode;
import fan.summer.fengyu.security.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * AI-invoked workflows must inherit the INVOKING context's permission mode — the historical
 * hardcoded FULL_ACCESS meant one approved {@code run_workflow_*} wrapper call (or an
 * unattended schedule) executed model-shaped commands with no sandbox, no step approvals,
 * and no rule floor. These tests pin the inheritance and the safe default.
 */
class WorkflowExecutionServicePermissionTest {

    @AfterEach
    void clearContext() {
        AiPermissionContext.clear();
    }

    private WorkflowExecutionService service() {
        SecurityContext security = mock(SecurityContext.class);
        when(security.currentUserId()).thenReturn(1L);
        WorkflowService workflows = mock(WorkflowService.class);
        when(workflows.get("wf-1")).thenReturn(new WorkflowDefinition(
                "wf-1", "n", "d", Map.of(), null, Map.of(), Map.of(), true, 1, null, null));
        when(workflows.compile("wf-1", Map.of(), true))
                .thenReturn(new AgentPlan("goal", List.of(), "reasoning"));
        // The draft path (chat-bound run_current_flow) compiles without the publication gate.
        when(workflows.compile("wf-1", Map.of(), false))
                .thenReturn(new AgentPlan("goal", List.of(), "reasoning"));
        return new WorkflowExecutionService(workflows,
                new AgentRunRegistry(security),
                mock(AgentRunPersistenceService.class),
                mock(AgentRunner.class));
    }

    @Test
    void startForAi_inheritsBoundPermissionMode() {
        AiPermissionContext.set(AiPermissionMode.FULL_ACCESS);
        AgentRun run = service().startForAi("wf-1", Map.of());
        assertEquals(AiPermissionMode.FULL_ACCESS, run.getConfig().effectivePermissionMode());
    }

    @Test
    void startForAi_defaultsToAskWhenNoContextIsBound() {
        AgentRun run = service().startForAi("wf-1", Map.of());
        assertEquals(AiPermissionMode.ASK_FOR_APPROVAL, run.getConfig().effectivePermissionMode(),
                "unbound callers must never fall back to FULL_ACCESS");
    }

    @Test
    void draftBindingRunsTheSameInheritedPermissionPath() {
        AiPermissionContext.set(AiPermissionMode.FULL_ACCESS);
        // The chat-bound tool reaches the runner through the same startForAi machinery; only
        // the publication requirement differs, so a draft under construction is conversable.
        AgentRun run = service().startForAi("wf-1", Map.of(), false);
        assertEquals(AiPermissionMode.FULL_ACCESS, run.getConfig().effectivePermissionMode());
    }

    @Test
    void aiInvocationReturnsLastActuallyCompletedBranchResult() {
        SecurityContext security = mock(SecurityContext.class);
        when(security.currentUserId()).thenReturn(1L);
        WorkflowService workflows = mock(WorkflowService.class);
        AgentPlan plan = new AgentPlan("branch", List.of(
                new AgentStep(0, "flow_if", Map.of(), "if", false),
                new AgentStep(1, "echo", Map.of(), "true", false),
                new AgentStep(2, "echo", Map.of(), "false", false)), "");
        when(workflows.get("wf-1")).thenReturn(new WorkflowDefinition(
                "wf-1", "n", "d", Map.of(), plan, Map.of(), Map.of(), true, 1, null, null));
        when(workflows.compile("wf-1", Map.of(), true)).thenReturn(plan);
        AgentRunner runner = mock(AgentRunner.class);
        doAnswer(invocation -> {
            AgentEventSink sink = invocation.getArgument(1);
            sink.onPlanReady(plan);
            sink.onStepComplete(0, "{\"branch\":\"true\"}");
            sink.onStepComplete(1, "{\"success\":true,\"summary\":\"sent\"}");
            sink.onStepSkipped(2);
            sink.onComplete("Completed 3 steps");
            return null;
        }).when(runner).run(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        AgentRunPersistenceService persistence = mock(AgentRunPersistenceService.class);
        when(persistence.persisting(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(1));
        WorkflowExecutionService execution = new WorkflowExecutionService(workflows,
                new AgentRunRegistry(security), persistence, runner);

        assertEquals("{\"success\":true,\"summary\":\"sent\"}",
                execution.executeForAi("wf-1", Map.of()));
    }
}
