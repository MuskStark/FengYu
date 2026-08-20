package fan.summer.fengyu.ai.tasks;

import fan.summer.fengyu.FengYuApplication;
import fan.summer.fengyu.ai.workflow.WorkflowExecutionService;
import fan.summer.fengyu.ai.workflow.WorkflowService;
import fan.summer.fengyu.database.entity.ai.WorkflowScheduleEntity;
import fan.summer.fengyu.database.repository.ai.WorkflowScheduleRepository;
import fan.summer.fengyu.security.NoopSecurityContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Proves schedules survive a fresh scheduler instance through the real JPA schema. */
@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = FengYuApplication.class)
class BackgroundTaskSchedulerPersistenceTest {

    @Autowired WorkflowScheduleRepository repository;

    @Test
    void persistsRestoresAndDurablyCancelsAnActiveSchedule() {
        BackgroundTaskScheduler first = scheduler();
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("optional", null);
        BackgroundTaskScheduler.Schedule created = first.create(
                "wf-persisted", inputs, 300, true, false);
        first.stopTicker();
        repository.flush();

        WorkflowScheduleEntity stored = repository.findById(created.id()).orElseThrow();
        assertEquals("ACTIVE", stored.getStatus());
        assertTrue(stored.getInputsJson().contains("\"optional\":null"));

        BackgroundTaskScheduler restored = scheduler();
        restored.recoverSchedules();
        assertEquals(1, restored.list().size());
        assertEquals(created.id(), restored.list().getFirst().get("scheduleId"));
        assertEquals(true, restored.list().getFirst().get("persistent"));

        assertTrue(restored.delete(created.id()));
        WorkflowScheduleEntity cancelled = repository.findById(created.id()).orElseThrow();
        assertEquals("CANCELLED", cancelled.getStatus());
        assertNull(cancelled.getClaimedAt());
        assertEquals(0, restored.list().size());
        restored.stopTicker();
    }

    @SuppressWarnings("unchecked")
    private BackgroundTaskScheduler scheduler() {
        ObjectProvider<WorkflowExecutionService> executions = mock(ObjectProvider.class);
        ObjectProvider<WorkflowService> workflows = mock(ObjectProvider.class);
        return new BackgroundTaskScheduler(new BackgroundTaskRegistry(), executions, workflows,
                repository, new NoopSecurityContext(), false);
    }
}
