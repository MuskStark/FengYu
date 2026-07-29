package fan.summer.fengyu.ai.agent;

import fan.summer.fengyu.FengYuApplication;
import fan.summer.fengyu.database.repository.ai.AgentRunEventRepository;
import fan.summer.fengyu.database.repository.ai.AgentRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Import(AgentRunPersistenceService.class)
@ActiveProfiles("test")
@ContextConfiguration(classes = FengYuApplication.class)
class AgentRunPersistenceServiceTest {
    @Autowired AgentRunPersistenceService persistence;
    @Autowired AgentRunRepository runs;
    @Autowired AgentRunEventRepository events;

    @Test
    void persistsSnapshotEventsAndBuildsReviewedResumeState() {
        AgentRun run = new AgentRun("run-1", "finish work",
                new AgentRunConfig(false, false, true, 2));
        AgentPlan plan = new AgentPlan("finish work", List.of(
                new AgentStep(0, "echo", Map.of("text", "one"), "one", false),
                new AgentStep(1, "echo", Map.of("text", "{{steps.0.result}}"), "two", false)
        ), "test");
        run.setPlan(plan);
        persistence.create(run, null);

        AgentEventSink sink = persistence.persisting(run, new NoopSink());
        sink.onPlanReady(plan);
        run.addExecution(new StepExecution(0, StepStatus.COMPLETED, "result-one"));
        sink.onStepComplete(0, "result-one");
        run.setStatus(AgentRunStatus.FAILED);
        sink.onError("interrupted");

        AgentRunPersistenceService.RunDetail detail = persistence.detail("run-1");
        assertEquals(AgentRunStatus.FAILED.name(), detail.status());
        assertEquals("result-one", detail.executions().getFirst().result());
        assertTrue(detail.events().stream().anyMatch(event -> "step_complete".equals(event.type())));

        AgentRunPersistenceService.ResumeState resume = persistence.resumeState("run-1");
        assertTrue(resume.config().requirePlanApproval());
        assertEquals(1, resume.completedExecutions().size());
        assertNotNull(resume.plan());
    }

    @Test
    void marksInFlightRunsInterruptedAtStartup() {
        AgentRun run = new AgentRun("run-2", "work",
                new AgentRunConfig(false, false, false, 0));
        persistence.create(run, null);
        run.setStatus(AgentRunStatus.EXECUTING);
        persistence.updateSnapshot(run, null, null);

        persistence.markInterruptedRuns();

        AgentRunPersistenceService.RunDetail detail = persistence.detail("run-2");
        assertEquals(AgentRunStatus.FAILED.name(), detail.status());
        assertTrue(detail.error().contains("restart"));
        assertTrue(detail.events().stream().anyMatch(event -> "interrupted".equals(event.type())));
    }

    private static final class NoopSink implements AgentEventSink {
        @Override public void onPlanToken(String delta) {}
        @Override public void onPlanReady(AgentPlan plan) {}
        @Override public void onPlanApprovalRequested() {}
        @Override public void onStepStart(int index) {}
        @Override public void onStepComplete(int index, String result) {}
        @Override public void onStepApprovalRequested(int index) {}
        @Override public void onComplete(String summary) {}
        @Override public void onError(String message) {}
    }
}
