package fan.summer.fengyu.ai.tasks;

import fan.summer.fengyu.ai.metrics.BackgroundTaskMetrics;
import fan.summer.fengyu.security.NoopSecurityContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scheduler's Micrometer vocabulary, calibrated against Kubernetes API Priority
 * and Fairness and Temporal's schedule-to-start semantics: dispatched and rejected
 * counters per priority and capacity scope, the queue-wait distribution split by
 * outcome, and the per-priority in-queue/oldest-wait gauges behind the delay alert.
 */
class BackgroundTaskMetricsTest {

    private static final BackgroundTaskRegistry.TaskBody SLOW = task -> {
        while (!task.cancelRequested()) {
            Thread.sleep(20);
        }
        throw new IllegalStateException("cancelled");
    };

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicReference<BackgroundTaskRegistry> registryRef = new AtomicReference<>();

    @AfterEach
    void stopEverything() {
        BackgroundTaskRegistry registry = registryRef.get();
        if (registry != null) {
            registry.list().forEach(snapshot ->
                    registry.kill((String) snapshot.get("taskId")));
        }
    }

    private BackgroundTaskRegistry registry(int running, int queued) {
        BackgroundTaskMetrics metrics = new BackgroundTaskMetrics(meters);
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry(
                null, new NoopSecurityContext(), running, queued, queued, queued, queued,
                queued, queued, metrics);
        registryRef.set(registry);
        return registry;
    }

    private double dispatched(String priority) {
        io.micrometer.core.instrument.Counter counter = meters
                .find("fengyu.bg.tasks.dispatched").tag("priority", priority).counter();
        return counter == null ? 0 : counter.count();
    }

    private double rejected(String priority, String reason) {
        io.micrometer.core.instrument.Counter counter = meters
                .find("fengyu.bg.tasks.rejected")
                .tag("priority", priority).tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    private double queueWaitCount(String priority, String outcome) {
        io.micrometer.core.instrument.Timer timer = meters
                .find("fengyu.bg.task.queue.wait")
                .tag("priority", priority).tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    private double inqueue(String priority) {
        return meters.get("fengyu.bg.queue.inqueue")
                .tag("priority", priority).gauge().value();
    }

    private double oldestWaitMs(String priority) {
        return meters.get("fengyu.bg.queue.oldest_wait_ms")
                .tag("priority", priority).gauge().value();
    }

    @Test
    void dispatchedAndQueueWaitAreRecordedPerPriority() {
        BackgroundTaskRegistry registry = registry(1, 16);
        BackgroundTaskRegistry.Task blocker = registry.submit("demo", "running", SLOW);
        await().atMost(Duration.ofSeconds(5))
                .until(() -> blocker.status() == BackgroundTaskRegistry.Status.RUNNING);
        registry.submit(BackgroundTaskRegistry.Priority.INTERACTIVE, "demo", "fast-1",
                task -> "done");
        registry.submit(BackgroundTaskRegistry.Priority.NORMAL, "demo", "fast-2",
                task -> "done");
        registry.submit(BackgroundTaskRegistry.Priority.BATCH, "demo", "fast-3",
                task -> "done");
        assertEquals(1, inqueue("interactive"));
        assertEquals(1, inqueue("normal"));
        assertEquals(1, inqueue("batch"));

        assertTrue(registry.kill(blocker.id()));
        await().atMost(Duration.ofSeconds(5)).until(() -> registry.list().stream()
                .allMatch(snapshot -> !"queued".equals(snapshot.get("status"))
                        && !"running".equals(snapshot.get("status"))));

        // The blocker itself counts as a dispatched normal task with ~zero queue wait.
        assertEquals(1, dispatched("interactive"));
        assertEquals(2, dispatched("normal"));
        assertEquals(1, dispatched("batch"));
        assertEquals(1, queueWaitCount("interactive", "executed"));
        assertEquals(2, queueWaitCount("normal", "executed"));
        assertEquals(1, queueWaitCount("batch", "executed"));
        assertEquals(0, queueWaitCount("interactive", "cancelled"));
        await().atMost(Duration.ofSeconds(5)).until(() -> inqueue("batch") == 0);
    }

    @Test
    void rejectionsAreCountedByPriorityAndCapacityScope() {
        BackgroundTaskRegistry registry = registryWithReservations();
        BackgroundTaskRegistry.Task blocker = registry.submit("demo", "running", SLOW);
        await().atMost(Duration.ofSeconds(5))
                .until(() -> blocker.status() == BackgroundTaskRegistry.Status.RUNNING);

        for (int i = 0; i < 4; i++) {
            registry.submit(BackgroundTaskRegistry.Priority.BATCH, "demo", "batch-" + i, SLOW);
        }
        org.junit.jupiter.api.Assertions.assertThrows(BackgroundTaskCapacityException.class,
                () -> registry.submit(BackgroundTaskRegistry.Priority.BATCH,
                        "demo", "batch-overflow", SLOW));
        for (int i = 0; i < 2; i++) {
            registry.submit(BackgroundTaskRegistry.Priority.NORMAL, "demo", "normal-" + i, SLOW);
        }
        org.junit.jupiter.api.Assertions.assertThrows(BackgroundTaskCapacityException.class,
                () -> registry.submit(BackgroundTaskRegistry.Priority.NORMAL,
                        "demo", "normal-overflow", SLOW));
        for (int i = 0; i < 2; i++) {
            registry.submit(BackgroundTaskRegistry.Priority.INTERACTIVE, "demo",
                    "interactive-" + i, SLOW);
        }
        org.junit.jupiter.api.Assertions.assertThrows(BackgroundTaskCapacityException.class,
                () -> registry.submit(BackgroundTaskRegistry.Priority.INTERACTIVE,
                        "demo", "interactive-overflow", SLOW));

        assertEquals(1, rejected("batch", "owner-priority"));
        assertEquals(1, rejected("normal", "owner-priority"));
        assertEquals(1, rejected("interactive", "owner"));
        assertEquals(0, rejected("interactive", "global-priority"));
    }

    /** registry(1 running, 12 queued, 8 per owner, 4 batch per owner, 6 non-interactive per owner). */
    private BackgroundTaskRegistry registryWithReservations() {
        BackgroundTaskMetrics metrics = new BackgroundTaskMetrics(meters);
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry(
                null, new NoopSecurityContext(), 1, 12, 8, 4, 6, 12, 12, metrics);
        registryRef.set(registry);
        return registry;
    }

    @Test
    void cancelledWhileQueuedRecordsItsQueueWait() {
        BackgroundTaskRegistry registry = registry(1, 4);
        BackgroundTaskRegistry.Task blocker = registry.submit("demo", "running", SLOW);
        await().atMost(Duration.ofSeconds(5))
                .until(() -> blocker.status() == BackgroundTaskRegistry.Status.RUNNING);
        BackgroundTaskRegistry.Task queued = registry.submit(
                BackgroundTaskRegistry.Priority.INTERACTIVE, "demo", "never-runs",
                task -> "should not run");
        await().atMost(Duration.ofSeconds(5))
                .until(() -> inqueue("interactive") == 1);

        assertTrue(registry.kill(queued.id()));
        await().atMost(Duration.ofSeconds(5))
                .until(() -> queued.status() == BackgroundTaskRegistry.Status.CANCELLED);
        await().atMost(Duration.ofSeconds(5)).until(() -> inqueue("interactive") == 0);

        assertEquals(0, dispatched("interactive"));
        assertEquals(1, queueWaitCount("interactive", "cancelled"));
        assertEquals(0, queueWaitCount("interactive", "executed"));
    }

    @Test
    void gaugesAndCapacityAttributeTheOldestWaitToOnePriority() throws Exception {
        BackgroundTaskRegistry registry = registry(1, 16);
        BackgroundTaskRegistry.Task blocker = registry.submit("demo", "running", SLOW);
        await().atMost(Duration.ofSeconds(5))
                .until(() -> blocker.status() == BackgroundTaskRegistry.Status.RUNNING);
        BackgroundTaskRegistry.Task batch = registry.submit(
                BackgroundTaskRegistry.Priority.BATCH, "demo", "aging", SLOW);
        await().atMost(Duration.ofSeconds(5))
                .until(() -> inqueue("batch") == 1);
        Thread.sleep(150);

        // The 30s delay alert can name the offending class: only batch is aging.
        assertEquals(0, oldestWaitMs("interactive"));
        assertEquals(0, oldestWaitMs("normal"));
        assertTrue(oldestWaitMs("batch") >= 100,
                "oldest batch wait was " + oldestWaitMs("batch"));
        BackgroundTaskRegistry.Capacity capacity = registry.capacity();
        assertEquals(0, capacity.oldestInteractiveQueueWaitMs());
        assertEquals(0, capacity.oldestNormalQueueWaitMs());
        assertTrue(capacity.oldestBatchQueueWaitMs() >= 100);
        assertTrue(Math.abs(capacity.oldestBatchQueueWaitMs() - capacity.oldestQueueWaitMs())
                <= 50, "global oldest wait should track the aging batch task");
        assertEquals(1, capacity.queuedBatch());
        assertTrue(registry.kill(batch.id()));
    }
}
