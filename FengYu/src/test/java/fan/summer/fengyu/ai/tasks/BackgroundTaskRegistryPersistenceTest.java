package fan.summer.fengyu.ai.tasks;

import fan.summer.fengyu.FengYuApplication;
import fan.summer.fengyu.database.entity.ai.BackgroundTaskEntity;
import fan.summer.fengyu.database.repository.ai.BackgroundTaskRepository;
import fan.summer.fengyu.security.NoopSecurityContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Proves task output and interrupted-run recovery through the real JPA schema. */
@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = FengYuApplication.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BackgroundTaskRegistryPersistenceTest {

    @Autowired BackgroundTaskRepository repository;

    @Test
    void completedOutputSurvivesAFreshRegistryInstance() throws Exception {
        BackgroundTaskRegistry first = registry();
        BackgroundTaskRegistry.Task submitted = first.submit(
                BackgroundTaskRegistry.Priority.INTERACTIVE,
                "workflow", "durable output", running -> "persisted result");
        assertEquals("persisted result", first.awaitOutput(submitted.id(), 5_000).get("output"));

        BackgroundTaskRegistry restored = registry();
        restored.recoverTasks();

        assertEquals("completed", restored.awaitOutput(submitted.id(), 0).get("status"));
        assertEquals("interactive", restored.awaitOutput(submitted.id(), 0).get("priority"));
        assertEquals("persisted result", restored.awaitOutput(submitted.id(), 0).get("output"));
        assertNotNull(restored.awaitOutput(submitted.id(), 0).get("startedAt"));
        assertNotNull(restored.awaitOutput(submitted.id(), 0).get("queueWaitMs"));
        assertNotNull(restored.awaitOutput(submitted.id(), 0).get("runDurationMs"));
        assertNotNull(restored.awaitOutput(submitted.id(), 0).get("completedAt"));
    }

    @Test
    void runningTaskIsRecoveredAsInterruptedFailure() throws Exception {
        BackgroundTaskEntity interrupted = new BackgroundTaskEntity();
        interrupted.setId("interrupted");
        interrupted.setUserId(1L);
        interrupted.setKind("workflow");
        interrupted.setDescription("was running");
        interrupted.setStatus("RUNNING");
        interrupted.setOutput("");
        interrupted.setCreatedAt(Instant.now());
        repository.save(interrupted);

        BackgroundTaskRegistry restored = registry();
        restored.recoverTasks();

        assertEquals("failed", restored.awaitOutput("interrupted", 0).get("status"));
        assertEquals("normal", restored.awaitOutput("interrupted", 0).get("priority"));
        assertEquals("Queued or running task interrupted by application restart; "
                        + "it was not replayed to avoid duplicate side effects.",
                restored.awaitOutput("interrupted", 0).get("output"));
        assertNotNull(restored.awaitOutput("interrupted", 0).get("startedAt"));
        assertNotNull(restored.awaitOutput("interrupted", 0).get("completedAt"));
    }

    @Test
    void queuedTaskIsRecoveredAsInterruptedFailureWithoutReplay() throws Exception {
        BackgroundTaskEntity queued = new BackgroundTaskEntity();
        queued.setId("queued");
        queued.setUserId(1L);
        queued.setKind("workflow");
        queued.setDescription("was queued");
        queued.setStatus("QUEUED");
        queued.setOutput("");
        queued.setCreatedAt(Instant.now());
        repository.save(queued);

        BackgroundTaskRegistry restored = registry();
        restored.recoverTasks();

        assertEquals("failed", restored.awaitOutput("queued", 0).get("status"));
        assertEquals("Queued or running task interrupted by application restart; "
                        + "it was not replayed to avoid duplicate side effects.",
                restored.awaitOutput("queued", 0).get("output"));
        assertNull(restored.awaitOutput("queued", 0).get("startedAt"));
        assertNotNull(restored.awaitOutput("queued", 0).get("queueWaitMs"));
        assertNull(restored.awaitOutput("queued", 0).get("runDurationMs"));
        assertNotNull(restored.awaitOutput("queued", 0).get("completedAt"));
    }

    private BackgroundTaskRegistry registry() {
        return new BackgroundTaskRegistry(repository, new NoopSecurityContext());
    }
}
