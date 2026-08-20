package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.agent.AgentRunPersistenceService;
import fan.summer.fengyu.ai.agent.AgentRunRegistry;
import fan.summer.fengyu.ai.agent.AgentRunner;
import fan.summer.fengyu.ai.config.AiToolRegistry;
import fan.summer.fengyu.ai.tasks.BackgroundTaskRegistry;
import fan.summer.fengyu.ai.workflow.WorkflowExecutionService;
import fan.summer.fengyu.ai.workflow.WorkflowService;
import fan.summer.fengyu.security.NoopSecurityContext;
import fan.summer.fengyu.web.StreamTicketService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class AgentControllerBackgroundTaskTest {

    @Test
    void exposesBoundedQueueCapacityWithoutTaskDetails() {
        BackgroundTaskRegistry tasks = new BackgroundTaskRegistry();
        BackgroundTaskRegistry.Task running = tasks.submit("test", "private task", task -> {
            while (!task.cancelRequested()) Thread.sleep(10);
            throw new IllegalStateException("cancelled");
        });
        await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> running.status() == BackgroundTaskRegistry.Status.RUNNING);
        AgentController controller = new AgentController(
                mock(AgentRunner.class), new AgentRunRegistry(new NoopSecurityContext()),
                mock(AgentRunPersistenceService.class), mock(AiToolRegistry.class),
                mock(WorkflowService.class), mock(WorkflowExecutionService.class),
                new StreamTicketService(), null, null);
        ReflectionTestUtils.setField(controller, "backgroundTasks", tasks);

        BackgroundTaskRegistry.Capacity capacity = controller.backgroundTaskCapacity();

        assertEquals(1, capacity.running());
        assertEquals(0, capacity.queued());
        assertEquals(16, capacity.runningLimit());
        assertEquals(128, capacity.queueLimit());
        assertEquals(1, capacity.ownedRunning());
        assertEquals(32, capacity.ownerQueueLimit());
        assertEquals(32, capacity.ownedQueueAvailable());
        assertEquals(false, capacity.ownerSaturated());
        assertEquals(64, capacity.batchQueueLimit());
        assertEquals(96, capacity.nonInteractiveQueueLimit());
        assertEquals(16, capacity.ownerBatchQueueLimit());
        assertEquals(24, capacity.ownerNonInteractiveQueueLimit());
        assertEquals(0, capacity.queuedInteractive());
        assertEquals(0, capacity.queuedNormal());
        assertEquals(0, capacity.queuedBatch());
        assertEquals(1, capacity.activeOwners());
        assertEquals(0, capacity.oldestQueueWaitMs());
        assertEquals(false, capacity.saturated());
        assertEquals(BackgroundTaskRegistry.SCHEDULING_POLICY, capacity.schedulingPolicy());
        tasks.kill(running.id());
    }
}
