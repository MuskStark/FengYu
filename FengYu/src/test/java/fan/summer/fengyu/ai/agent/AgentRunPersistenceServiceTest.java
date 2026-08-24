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
        sink.onStepRetry(0, 2, 3, 500, "temporary outage");
        sink.onStepSkipped(1);
        run.addExecution(new StepExecution(0, StepStatus.COMPLETED, "result-one"));
        sink.onStepComplete(0, "result-one");
        run.setStatus(AgentRunStatus.FAILED);
        sink.onError("interrupted");

        AgentRunPersistenceService.RunDetail detail = persistence.detail("run-1");
        assertEquals(AgentRunStatus.FAILED.name(), detail.status());
        assertEquals("result-one", detail.executions().getFirst().result());
        assertTrue(detail.events().stream().anyMatch(event -> "step_complete".equals(event.type())));
        var committed = detail.events().stream()
                .filter(event -> "step_complete".equals(event.type())).findFirst().orElseThrow();
        assertEquals("run-1:step:0", committed.data().get("invocationId"));
        assertEquals("committed", committed.data().get("phase"));
        assertTrue(detail.events().stream().anyMatch(event -> "step_retry".equals(event.type())
                && Integer.valueOf(2).equals(event.data().get("nextAttempt"))));
        assertTrue(detail.events().stream().anyMatch(event -> "step_skipped".equals(event.type())));

        AgentRunPersistenceService.ResumeState resume = persistence.resumeState("run-1");
        assertTrue(resume.config().requirePlanApproval());
        assertEquals(1, resume.completedExecutions().size());
        assertNotNull(resume.plan());
    }

    @Test
    void upstreamInputReferencesSurvivePlanJsonRoundTrip() {
        AgentStep bound = new AgentStep(0, "excel_complex_config",
                Map.of("filePath", "/data/a.xlsx"), "configure", false);
        AgentPlan plan = new AgentPlan("split", List.of(bound,
                new AgentStep(1, "mail", Map.of("file", "{{steps.0.input.filePath}}"), "mail", false)),
                "test");

        AgentRun run = new AgentRun("run-bindings", "split", new AgentRunConfig(false, false, false, 0));
        run.setPlan(plan);
        run.setStatus(AgentRunStatus.FAILED);
        persistence.create(run, null);

        AgentRunPersistenceService.ResumeState resume = persistence.resumeState("run-bindings");
        assertNotNull(resume.plan());
        assertEquals("{{steps.0.input.filePath}}",
                resume.plan().steps().get(1).args().get("file"));
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
        assertEquals(AgentRunStatus.RECOVERY_REQUIRED.name(), detail.status());
        assertTrue(detail.error().contains("restart"));
        assertTrue(detail.events().stream().anyMatch(event -> "recovery_required".equals(event.type())));
    }

    @Test
    void refusesResumeWhenAnUnfinishedStepNeedsAnExpiredFileGrant() {
        AgentRun run = new AgentRun("run-file", "read file",
                new AgentRunConfig(false, false, false, 0));
        run.setPlan(new AgentPlan("read file", List.of(
                new AgentStep(0, "read", Map.of("path", "@file:workbook"), "read", false)), "test"));
        run.setStatus(AgentRunStatus.FAILED);
        persistence.create(run, null);

        var error = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> persistence.resumeState("run-file"));
        assertTrue(error.getMessage().contains("file grants expired"));
    }

    @Test
    void refusesResumeWhenARemainingStepReferencesACompletedFileInput() {
        AgentRun run = new AgentRun("run-file-input", "reuse file path",
                new AgentRunConfig(false, false, false, 0));
        run.setPlan(new AgentPlan("reuse file path", List.of(
                new AgentStep(0, "read", Map.of("path", "@file:workbook"), "read", false),
                new AgentStep(1, "copy", Map.of("path", "{{steps.0.input.path}}"), "copy", false)),
                "test"));
        run.addExecution(new StepExecution(0, StepStatus.COMPLETED, "{}"));
        run.setStatus(AgentRunStatus.FAILED);
        persistence.create(run, null);

        var error = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> persistence.resumeState("run-file-input"));
        assertTrue(error.getMessage().contains("file grants expired"));
    }

    @Test
    void searchMatchesGoalSummaryAndErrorText() {
        AgentRun run = new AgentRun("run-search", "Split quarterly invoices",
                new AgentRunConfig(false, false, false, 0));
        persistence.create(run, null);
        run.setStatus(AgentRunStatus.FAILED);
        persistence.updateSnapshot(run, null, "Excel column not found");

        assertTrue(persistence.search("invoices", 10).stream()
                .anyMatch(summary -> summary.id().equals("run-search")));
        assertTrue(persistence.search("column", 10).stream()
                .anyMatch(summary -> summary.id().equals("run-search")));
        assertTrue(persistence.search("nothing-matches-this", 10).stream()
                .noneMatch(summary -> summary.id().equals("run-search")));
    }

    @Test
    void forkCopiesThePlanWithoutInheritingCompletedSteps() {
        AgentRun run = new AgentRun("run-fork", "goal",
                new AgentRunConfig(false, false, false, 0));
        AgentPlan plan = new AgentPlan("goal", List.of(
                new AgentStep(0, "echo", Map.of(), "one", false),
                new AgentStep(1, "echo", Map.of(), "two", false)), "r");
        run.setPlan(plan);
        persistence.create(run, null);
        persistence.persisting(run, new NoopSink()).onPlanReady(plan);
        run.addExecution(new StepExecution(0, StepStatus.COMPLETED, "done-0"));
        run.setStatus(AgentRunStatus.COMPLETED);
        persistence.updateSnapshot(run, "finished", null);

        AgentRunPersistenceService.ResumeState fork = persistence.forkState("run-fork");
        assertEquals(2, fork.plan().steps().size());
        assertEquals(0, fork.completedExecutions().size());
        assertTrue(fork.config().requirePlanApproval());
        assertEquals("run-fork", fork.resumedFrom());
    }

    @Test
    void rewindKeepsTheFullPlanAndInheritsOnlyEarlierCompletions() {
        AgentRun run = new AgentRun("run-rewind", "goal",
                new AgentRunConfig(false, false, false, 0));
        AgentPlan plan = new AgentPlan("goal", List.of(
                new AgentStep(0, "echo", Map.of(), "one", false),
                new AgentStep(1, "echo", Map.of(), "two", false, List.of(0)),
                new AgentStep(2, "excel_execute", Map.of(), "three", false, List.of(1))), "r");
        run.setPlan(plan);
        persistence.create(run, null);
        AgentEventSink sink = persistence.persisting(run, new NoopSink());
        sink.onPlanReady(plan);
        run.addExecution(new StepExecution(0, StepStatus.COMPLETED, "done-0"));
        run.addExecution(new StepExecution(1, StepStatus.COMPLETED, "done-1"));
        run.addExecution(new StepExecution(2, StepStatus.FAILED, "boom"));
        run.setStatus(AgentRunStatus.FAILED);
        sink.onError("step 2 failed: boom");

        AgentRunPersistenceService.ResumeState rewind = persistence.rewindState("run-rewind", 1);
        // The FULL plan is preserved — the runner skips inherited completions (step 0)
        // and re-executes from step 1 onward. Truncating instead would leave nothing to run.
        assertEquals(3, rewind.plan().steps().size());
        assertEquals(1, rewind.completedExecutions().size());
        assertEquals(0, rewind.completedExecutions().getFirst().index());
        // Rewinding to step 0 re-runs everything (no completions inherited).
        AgentRunPersistenceService.ResumeState full = persistence.rewindState("run-rewind", 0);
        assertEquals(3, full.plan().steps().size());
        assertEquals(0, full.completedExecutions().size());
    }

    @Test
    void rewindRejectsActiveRunsAndOutOfRangeBoundaries() {
        AgentRun active = new AgentRun("run-active", "goal",
                new AgentRunConfig(false, false, false, 0));
        persistence.create(active, null);
        active.setStatus(AgentRunStatus.EXECUTING);
        persistence.updateSnapshot(active, null, null);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> persistence.rewindState("run-active", 0));

        AgentRun done = new AgentRun("run-done", "goal",
                new AgentRunConfig(false, false, false, 0));
        AgentPlan plan = new AgentPlan("goal", List.of(
                new AgentStep(0, "echo", Map.of(), "one", false)), "r");
        done.setPlan(plan);
        persistence.create(done, null);
        persistence.persisting(done, new NoopSink()).onPlanReady(plan);
        done.setStatus(AgentRunStatus.COMPLETED);
        persistence.updateSnapshot(done, "ok", null);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> persistence.rewindState("run-done", 1));
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
