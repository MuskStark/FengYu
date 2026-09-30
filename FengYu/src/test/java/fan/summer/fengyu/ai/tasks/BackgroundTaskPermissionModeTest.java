package fan.summer.fengyu.ai.tasks;

import fan.summer.fengyu.ai.agent.AgentRun;
import fan.summer.fengyu.ai.tools.AiPermissionContext;
import fan.summer.fengyu.ai.tools.AiPermissionMode;
import fan.summer.fengyu.ai.tools.ToolEffect;
import fan.summer.fengyu.ai.workflow.WorkflowExecutionService;
import fan.summer.fengyu.database.entity.ai.WorkflowScheduleEntity;
import fan.summer.fengyu.database.repository.ai.WorkflowScheduleRepository;
import fan.summer.fengyu.security.NoopSecurityContext;
import fan.summer.fengyu.security.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Background tasks and schedules run their workflow bodies on registry worker threads that
 * carry no permission context of their own — both entry points must carry the SUBMITTING
 * chat's mode across that thread boundary instead of letting {@code startForAi} fall back to
 * an unrelated default.
 */
class BackgroundTaskPermissionModeTest {

    @AfterEach
    void clearContext() {
        AiPermissionContext.clear();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<WorkflowExecutionService> provider(WorkflowExecutionService svc) {
        ObjectProvider<WorkflowExecutionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(svc);
        return provider;
    }

    @Test
    @SuppressWarnings("unchecked")
    void submitWorkflowRunsUnderTheSubmittingChatsMode() throws Exception {
        BackgroundTaskRegistry tasks = new BackgroundTaskRegistry();
        List<AiPermissionMode> seen = new CopyOnWriteArrayList<>();
        WorkflowExecutionService execution = mock(WorkflowExecutionService.class);
        when(execution.startForAi(anyString(), any())).thenAnswer(inv -> {
            seen.add(AiPermissionContext.current());
            return mock(AgentRun.class);
        });
        when(execution.waitForAiRun(any())).thenReturn("done");

        BackgroundTaskTools tools = new BackgroundTaskTools(tasks,
                provider(execution), mock(BackgroundTaskScheduler.class));

        AiPermissionContext.set(AiPermissionMode.FULL_ACCESS);
        String submitted = tools.submitWorkflow("wf-1", "{}");
        AiPermissionContext.clear();

        String taskId = taskIdOf(submitted);
        tasks.awaitOutput(taskId, 10_000);
        assertEquals(List.of(AiPermissionMode.FULL_ACCESS), seen,
                "the registry worker thread must see the submitting chat's mode");
    }

    @Test
    void capacityToolIsReadOnlyAndReportsBoundedQueuePressure() {
        BackgroundTaskRegistry tasks = new BackgroundTaskRegistry();
        BackgroundTaskTools tools = new BackgroundTaskTools(tasks,
                provider(mock(WorkflowExecutionService.class)),
                mock(BackgroundTaskScheduler.class));

        assertEquals(ToolEffect.READ, tools.effectFor("task_capacity"));
        String capacity = tools.capacity();
        assertTrue(capacity.contains("\"runningLimit\":16"));
        assertTrue(capacity.contains("\"queueLimit\":128"));
        assertTrue(capacity.contains("\"ownedQueued\":0"));
        assertTrue(capacity.contains("\"ownerQueueLimit\":32"));
        assertTrue(capacity.contains("\"ownedQueueAvailable\":32"));
        assertTrue(capacity.contains("\"batchQueueLimit\":64"));
        assertTrue(capacity.contains("\"nonInteractiveQueueLimit\":96"));
        assertTrue(capacity.contains("\"ownerBatchQueueLimit\":16"));
        assertTrue(capacity.contains("\"ownerNonInteractiveQueueLimit\":24"));
        assertTrue(capacity.contains("\"queuedInteractive\":0"));
        assertTrue(capacity.contains("\"schedulingPolicy\":\""
                + BackgroundTaskRegistry.SCHEDULING_POLICY + "\""));
    }

    @Test
    void submitToolReturnsStructuredRetryAdviceWhenQueueIsFull() {
        BackgroundTaskRegistry tasks = new BackgroundTaskRegistry(
                null, new NoopSecurityContext(), 1, 0);
        BackgroundTaskRegistry.Task blocker = tasks.submit("demo", "block", running -> {
            while (!running.cancelRequested()) Thread.sleep(10);
            throw new IllegalStateException("cancelled");
        });
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> blocker.status() == BackgroundTaskRegistry.Status.RUNNING);
        BackgroundTaskTools tools = new BackgroundTaskTools(tasks,
                provider(mock(WorkflowExecutionService.class)),
                mock(BackgroundTaskScheduler.class));

        String response = tools.submitWorkflow("wf-overloaded", "{}");

        assertTrue(response.contains("\"taskId\":null"));
        assertTrue(response.contains("\"retryable\":true"));
        assertTrue(response.contains("\"capacityScope\":\"global\""));
        assertTrue(response.contains("\"retryAfterSeconds\":1"));
        tasks.kill(blocker.id());
    }

    @Test
    @SuppressWarnings("unchecked")
    void scheduledFiresRestoreTheCreatingChatsMode() throws Exception {
        BackgroundTaskRegistry tasks = new BackgroundTaskRegistry();
        List<AiPermissionMode> seen = new CopyOnWriteArrayList<>();
        WorkflowExecutionService execution = mock(WorkflowExecutionService.class);
        when(execution.startForAi(anyString(), any())).thenAnswer(inv -> {
            seen.add(AiPermissionContext.current());
            return mock(AgentRun.class);
        });
        when(execution.waitForAiRun(any())).thenReturn("done");

        ObjectProvider<fan.summer.fengyu.ai.workflow.WorkflowService> workflowsProvider =
                mock(ObjectProvider.class);
        WorkflowScheduleRepository repository = mock(WorkflowScheduleRepository.class);
        when(repository.save(any(WorkflowScheduleEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        SecurityContext security = mock(SecurityContext.class);
        when(security.currentUserId()).thenReturn(1L);
        BackgroundTaskScheduler scheduler = new BackgroundTaskScheduler(tasks,
                provider(execution), workflowsProvider, repository, security, false);

        AiPermissionContext.set(AiPermissionMode.FULL_ACCESS);
        BackgroundTaskScheduler.Schedule schedule =
                scheduler.create("wf-1", Map.of(), 60, false, true);
        AiPermissionContext.clear();

        scheduler.tick();
        tasks.awaitOutput(schedule.lastTaskId(), 10_000);
        // FULL_ACCESS is distinctive here: the safe unbound default is ASK_FOR_APPROVAL, so a
        // regression back to "read whatever the worker thread happens to carry" would surface
        // as ASK entries. The background ticker may race the test's tick into the same first
        // fire window, so assert on every observed mode rather than an exact count.
        org.junit.jupiter.api.Assertions.assertFalse(seen.isEmpty(), "schedule never fired");
        for (AiPermissionMode mode : seen) {
            assertEquals(AiPermissionMode.FULL_ACCESS, mode,
                    "a fire on the scheduler's own thread must run under the schedule's captured mode");
        }
    }

    private static String taskIdOf(String submittedJson) {
        int i = submittedJson.indexOf("\"taskId\":\"");
        if (i < 0) throw new AssertionError("no taskId in: " + submittedJson);
        int start = i + "\"taskId\":\"".length();
        return submittedJson.substring(start, submittedJson.indexOf('"', start));
    }
}
