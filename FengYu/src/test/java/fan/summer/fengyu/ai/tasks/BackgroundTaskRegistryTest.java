package fan.summer.fengyu.ai.tasks;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The host-level task registry: submit/poll/wait/kill semantics and concurrency caps. */
class BackgroundTaskRegistryTest {

    private static final BackgroundTaskRegistry.TaskBody SLOW = task -> {
        while (!task.cancelRequested()) {
            Thread.sleep(20);
        }
        throw new IllegalStateException("cancelled");
    };

    @Test
    void submitReturnsImmediatelyAndCapturesOutput() {
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry();
        BackgroundTaskRegistry.Task task = registry.submit("demo", "double",
                running -> "result: 42");
        assertEquals("double", task.description());
        await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> task.status() == BackgroundTaskRegistry.Status.COMPLETED);
        assertEquals("result: 42", task.output());
    }

    @Test
    void awaitOutputBlocksUntilCompletion() throws Exception {
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry();
        BackgroundTaskRegistry.Task task = registry.submit("demo", "slow",
                running -> {
                    Thread.sleep(150);
                    return "done";
                });
        Map<String, Object> snapshot = registry.awaitOutput(task.id(), 5_000);
        assertEquals("completed", snapshot.get("status"));
        assertEquals("done", snapshot.get("output"));
    }

    @Test
    void unknownTaskReturnsNullAndKillIsFalse() throws Exception {
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry();
        assertEquals(null, registry.awaitOutput("nope", 0));
        assertFalse(registry.kill("nope"));
    }

    @Test
    void cooperativeKillCancelsTheBody() {
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry();
        AtomicInteger cancellerRuns = new AtomicInteger();
        BackgroundTaskRegistry.Task task = registry.submit("demo", "loop", running -> {
            running.canceller = cancellerRuns::incrementAndGet;
            while (!running.cancelRequested()) {
                Thread.sleep(10);
            }
            throw new IllegalStateException("cancelled by request");
        });
        await().atMost(java.time.Duration.ofSeconds(5)).until(() -> registry.kill(task.id()));
        await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> task.status() == BackgroundTaskRegistry.Status.CANCELLED);
        assertEquals(1, cancellerRuns.get());
        // A finished task cannot be killed again.
        assertFalse(registry.kill(task.id()));
    }

    @Test
    void waitAnyReturnsWhenTheFirstTaskFinishes() throws Exception {
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry();
        BackgroundTaskRegistry.Task slow = registry.submit("demo", "slow", SLOW);
        BackgroundTaskRegistry.Task fast = registry.submit("demo", "fast",
                running -> "fast done");
        List<Map<String, Object>> snapshots = registry.waitMany(
                List.of(slow.id(), fast.id()), BackgroundTaskRegistry.WaitMode.ANY, 5_000);
        Map<String, Object> fastSnapshot = snapshots.stream()
                .filter(s -> s.get("taskId").equals(fast.id())).findFirst().orElseThrow();
        assertEquals("completed", fastSnapshot.get("status"));
        registry.kill(slow.id());
    }

    @Test
    void processBackedKillEscalatesWithSigtermThenSigkill() throws Exception {
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry();
        BackgroundTaskRegistry.Task task = registry.submit("demo", "sleep-process", running -> {
            Process sleep = new ProcessBuilder("/bin/sh", "-c", "sleep 30").start();
            running.process = sleep::toHandle;
            int exit = (int) sleep.waitFor();
            if (running.cancelRequested() || exit != 0) {
                throw new IllegalStateException("process killed (exit " + exit + ")");
            }
            return String.valueOf(exit);
        });
        await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> task.process != null);
        assertTrue(registry.kill(task.id()));
        await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> task.status() != BackgroundTaskRegistry.Status.RUNNING);
        assertEquals(BackgroundTaskRegistry.Status.CANCELLED, task.status());
    }

    @Test
    void concurrentSubmissionIsCapped() throws Exception {
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry();
        for (int i = 0; i < 16; i++) {
            registry.submit("demo", "block-" + i, SLOW);
        }
        // The 17th concurrent submission is rejected until tasks finish.
        assertThrows(IllegalStateException.class,
                () -> registry.submit("demo", "overflow", SLOW));
        registry.list().forEach(snapshot ->
                registry.kill((String) snapshot.get("taskId")));
    }

    /** 6.1: the cap must hold under racing submissions, not just sequential ones. */
    @Test
    void racingSubmissionsNeverExceedTheCap() throws Exception {
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry();
        int attempts = 64;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Boolean>> results = new java.util.ArrayList<>();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(attempts)) {
            for (int i = 0; i < attempts; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        registry.submit("demo", "race", SLOW);
                        return true;
                    } catch (IllegalStateException capped) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int accepted = 0;
            for (var future : results) accepted += future.get() ? 1 : 0;
            assertEquals(16, accepted, "exactly the cap is accepted under contention");
            registry.list().forEach(snapshot ->
                    registry.kill((String) snapshot.get("taskId")));
        }
    }

    @Test
    void waitManyRejectsMoreThanTwentyIds() {
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry();
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 21; i++) ids.add("t" + i);
        assertThrows(IllegalArgumentException.class,
                () -> registry.waitMany(ids, BackgroundTaskRegistry.WaitMode.ANY, 0));
    }
}
