package fan.summer.fengyu.ai.tasks;

import fan.summer.fengyu.database.entity.ai.BackgroundTaskEntity;
import fan.summer.fengyu.database.repository.ai.BackgroundTaskRepository;
import fan.summer.fengyu.security.NoopSecurityContext;
import fan.summer.fengyu.security.SecurityContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        Map<String, Object> summary = registry.summary(task);
        assertTrue(summary.containsKey("startedAt"));
        assertTrue((long) summary.get("queueWaitMs") >= 0);
        assertTrue((long) summary.get("runDurationMs") >= 0);
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
            running.onCancel(cancellerRuns::incrementAndGet);
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
    void excessSubmissionsQueueUntilCapacityFrees() throws Exception {
        BackgroundTaskRegistry registry = registry(2, 3);
        for (int i = 0; i < 5; i++) registry.submit("demo", "block-" + i, SLOW);

        await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
            assertEquals(2, registry.list().stream()
                    .filter(row -> "running".equals(row.get("status"))).count());
            assertEquals(3, registry.list().stream()
                    .filter(row -> "queued".equals(row.get("status"))).count());
        });
        BackgroundTaskRegistry.Capacity capacity = registry.capacity();
        assertEquals(2, capacity.running());
        assertEquals(3, capacity.queued());
        assertEquals(2, capacity.runningLimit());
        assertEquals(3, capacity.queueLimit());
        assertEquals(0, capacity.available());
        assertEquals(2, capacity.ownedRunning());
        assertEquals(3, capacity.ownedQueued());
        assertEquals(3, capacity.ownerQueueLimit());
        assertEquals(0, capacity.ownedQueueAvailable());
        assertTrue(capacity.ownerSaturated());
        assertEquals(1, capacity.activeOwners());
        assertTrue(capacity.oldestQueueWaitMs() >= 0);
        assertTrue(capacity.saturated());
        assertEquals(BackgroundTaskRegistry.SCHEDULING_POLICY, capacity.schedulingPolicy());
        // Only the bounded overflow beyond running + queued capacity is rejected.
        assertThrows(BackgroundTaskCapacityException.class,
                () -> registry.submit("demo", "overflow", SLOW));
        registry.list().forEach(snapshot ->
                registry.kill((String) snapshot.get("taskId")));
    }

    @Test
    void capacityUsesSchedulerLedgerDuringThreadStartup() {
        BackgroundTaskRegistry registry = registry(2, 4, 2);
        registry.submit("demo", "running-a", SLOW);
        registry.submit("demo", "running-b", SLOW);
        registry.submit("demo", "queued-a", SLOW);
        registry.submit("demo", "queued-b", SLOW);

        BackgroundTaskRegistry.Capacity capacity = registry.capacity();
        assertEquals(2, capacity.running());
        assertEquals(2, capacity.queued());
        assertEquals(2, capacity.ownedRunning());
        assertEquals(2, capacity.ownedQueued());
        assertEquals(0, capacity.ownedQueueAvailable());
        assertTrue(capacity.ownerSaturated());

        registry.list().forEach(snapshot ->
                registry.kill((String) snapshot.get("taskId")));
    }

    @Test
    void oneOwnerCannotConsumeAnotherOwnersQueueShare() {
        AtomicLong currentUser = new AtomicLong(1L);
        SecurityContext security = mock(SecurityContext.class);
        when(security.currentUserId()).thenAnswer(ignored -> currentUser.get());
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry(
                null, security, 1, 4, 2);
        BackgroundTaskRegistry.Task blocker = registry.submit(1L, "demo", "running", SLOW);
        await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> blocker.status() == BackgroundTaskRegistry.Status.RUNNING);
        BackgroundTaskRegistry.Task ownerOneFirst =
                registry.submit(1L, "demo", "owner-1-a", SLOW);
        BackgroundTaskRegistry.Task ownerOneSecond =
                registry.submit(1L, "demo", "owner-1-b", SLOW);

        BackgroundTaskCapacityException ownerFull = assertThrows(
                BackgroundTaskCapacityException.class,
                () -> registry.submit(1L, "demo", "owner-1-overflow", SLOW));
        assertEquals("owner", ownerFull.capacityScope());
        assertEquals(2, registry.capacity().available(),
                "owner load shedding must leave global admission capacity for another owner");

        BackgroundTaskRegistry.Task ownerTwo =
                registry.submit(2L, "demo", "owner-2", SLOW);
        BackgroundTaskRegistry.Capacity capacity = registry.capacity();
        assertEquals(3, capacity.queued());
        assertEquals(2, capacity.ownerQueueLimit());
        assertEquals(0, capacity.ownedQueueAvailable());
        assertTrue(capacity.ownerSaturated());
        assertEquals(2, capacity.activeOwners());

        assertTrue(registry.kill(blocker.id()));
        assertTrue(registry.kill(ownerOneFirst.id()));
        assertTrue(registry.kill(ownerOneSecond.id()));
        currentUser.set(2L);
        assertTrue(registry.kill(ownerTwo.id()),
                "the second owner's admitted task must remain controllable");
        await().atMost(java.time.Duration.ofSeconds(5)).until(() -> List.of(
                blocker, ownerOneFirst, ownerOneSecond, ownerTwo).stream()
                .allMatch(task -> task.status() == BackgroundTaskRegistry.Status.CANCELLED));
    }

    @RepeatedTest(10)
    void admittedTasksAreFifoWithinOwnersAndRoundRobinAcrossOwners() {
        BackgroundTaskRegistry registry = registry(1, 8);
        BackgroundTaskRegistry.Task blocker = registry.submit(1L, "demo", "running", SLOW);
        await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> blocker.status() == BackgroundTaskRegistry.Status.RUNNING);
        List<String> starts = new CopyOnWriteArrayList<>();
        BackgroundTaskRegistry.Task ownerOneFirst = registry.submit(
                1L, "demo", "owner-1-a", task -> { starts.add("owner-1-a"); return "done"; });
        BackgroundTaskRegistry.Task ownerOneSecond = registry.submit(
                1L, "demo", "owner-1-b", task -> { starts.add("owner-1-b"); return "done"; });
        BackgroundTaskRegistry.Task ownerTwoFirst = registry.submit(
                2L, "demo", "owner-2-a", task -> { starts.add("owner-2-a"); return "done"; });
        BackgroundTaskRegistry.Task ownerTwoSecond = registry.submit(
                2L, "demo", "owner-2-b", task -> { starts.add("owner-2-b"); return "done"; });

        BackgroundTaskRegistry.Capacity contended = registry.capacity();
        assertEquals(2, contended.activeOwners());
        assertEquals(4, contended.queued());

        assertTrue(registry.kill(blocker.id()));
        await().atMost(java.time.Duration.ofSeconds(5)).until(() -> List.of(
                ownerOneFirst, ownerOneSecond, ownerTwoFirst, ownerTwoSecond).stream()
                .allMatch(task -> task.status() == BackgroundTaskRegistry.Status.COMPLETED));

        assertEquals(List.of("owner-1-a", "owner-2-a", "owner-1-b", "owner-2-b"), starts);
    }

    @RepeatedTest(10)
    void priorityQueuesUseWeightedTurnsWithoutStarvingBatch() {
        BackgroundTaskRegistry registry = registry(1, 16, 16, 16, 16);
        BackgroundTaskRegistry.Task blocker = registry.submit("demo", "running", SLOW);
        await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> blocker.status() == BackgroundTaskRegistry.Status.RUNNING);
        List<String> starts = new CopyOnWriteArrayList<>();
        BackgroundTaskRegistry.Task batch = registry.submit(
                BackgroundTaskRegistry.Priority.BATCH, "demo", "batch",
                task -> { starts.add("batch"); return "done"; });
        BackgroundTaskRegistry.Task normalOne = registry.submit(
                BackgroundTaskRegistry.Priority.NORMAL, "demo", "normal-1",
                task -> { starts.add("normal-1"); return "done"; });
        BackgroundTaskRegistry.Task normalTwo = registry.submit(
                BackgroundTaskRegistry.Priority.NORMAL, "demo", "normal-2",
                task -> { starts.add("normal-2"); return "done"; });
        List<BackgroundTaskRegistry.Task> interactive = new java.util.ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            String name = "interactive-" + i;
            interactive.add(registry.submit(BackgroundTaskRegistry.Priority.INTERACTIVE,
                    "demo", name, task -> { starts.add(name); return "done"; }));
        }

        assertTrue(registry.kill(blocker.id()));
        List<BackgroundTaskRegistry.Task> queued = new java.util.ArrayList<>(interactive);
        queued.add(normalOne);
        queued.add(normalTwo);
        queued.add(batch);
        await().atMost(java.time.Duration.ofSeconds(5)).until(() -> queued.stream()
                .allMatch(task -> task.status() == BackgroundTaskRegistry.Status.COMPLETED));

        assertEquals(List.of("interactive-1", "interactive-2", "interactive-3",
                "interactive-4", "normal-1", "normal-2", "batch"), starts);
    }

    @Test
    void priorityReservationsPreserveInteractiveAdmission() {
        BackgroundTaskRegistry registry = registry(1, 12, 8, 4, 6);
        BackgroundTaskRegistry.Task blocker = registry.submit("demo", "running", SLOW);
        await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> blocker.status() == BackgroundTaskRegistry.Status.RUNNING);

        for (int i = 0; i < 4; i++) {
            registry.submit(BackgroundTaskRegistry.Priority.BATCH,
                    "demo", "batch-" + i, SLOW);
        }
        BackgroundTaskCapacityException batchFull = assertThrows(
                BackgroundTaskCapacityException.class,
                () -> registry.submit(BackgroundTaskRegistry.Priority.BATCH,
                        "demo", "batch-overflow", SLOW));
        assertEquals("owner-priority", batchFull.capacityScope());
        assertEquals("batch", batchFull.capacityPriority());

        for (int i = 0; i < 2; i++) {
            registry.submit(BackgroundTaskRegistry.Priority.NORMAL,
                    "demo", "normal-" + i, SLOW);
        }
        BackgroundTaskCapacityException normalFull = assertThrows(
                BackgroundTaskCapacityException.class,
                () -> registry.submit(BackgroundTaskRegistry.Priority.NORMAL,
                        "demo", "normal-overflow", SLOW));
        assertEquals("owner-priority", normalFull.capacityScope());
        assertEquals("normal", normalFull.capacityPriority());

        registry.submit(BackgroundTaskRegistry.Priority.INTERACTIVE,
                "demo", "interactive-1", SLOW);
        registry.submit(BackgroundTaskRegistry.Priority.INTERACTIVE,
                "demo", "interactive-2", SLOW);
        BackgroundTaskCapacityException ownerFull = assertThrows(
                BackgroundTaskCapacityException.class,
                () -> registry.submit(BackgroundTaskRegistry.Priority.INTERACTIVE,
                        "demo", "interactive-overflow", SLOW));
        assertEquals("owner", ownerFull.capacityScope());

        BackgroundTaskRegistry.Capacity capacity = registry.capacity();
        assertEquals(2, capacity.queuedInteractive());
        assertEquals(2, capacity.queuedNormal());
        assertEquals(4, capacity.queuedBatch());
        assertEquals(4, capacity.ownerBatchQueueLimit());
        assertEquals(6, capacity.ownerNonInteractiveQueueLimit());
        registry.list().forEach(snapshot ->
                registry.kill((String) snapshot.get("taskId")));
    }

    @Test
    void globalPriorityReservationsSurviveMultipleOwnerQueues() {
        BackgroundTaskRegistry registry = registry(1, 12, 12, 12, 12, 4, 8);
        BackgroundTaskRegistry.Task blocker = registry.submit("demo", "running", SLOW);
        await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> blocker.status() == BackgroundTaskRegistry.Status.RUNNING);

        for (int i = 0; i < 4; i++) {
            registry.submit(BackgroundTaskRegistry.Priority.BATCH,
                    "demo", "batch-" + i, SLOW);
        }
        BackgroundTaskCapacityException batchFull = assertThrows(
                BackgroundTaskCapacityException.class,
                () -> registry.submit(2L, BackgroundTaskRegistry.Priority.BATCH,
                        "demo", "batch-overflow", SLOW));
        assertEquals("global-priority", batchFull.capacityScope());
        assertEquals("batch", batchFull.capacityPriority());

        for (int i = 0; i < 4; i++) {
            registry.submit(BackgroundTaskRegistry.Priority.NORMAL,
                    "demo", "normal-" + i, SLOW);
        }
        BackgroundTaskCapacityException normalFull = assertThrows(
                BackgroundTaskCapacityException.class,
                () -> registry.submit(2L, BackgroundTaskRegistry.Priority.NORMAL,
                        "demo", "normal-overflow", SLOW));
        assertEquals("global-priority", normalFull.capacityScope());
        assertEquals("normal", normalFull.capacityPriority());

        for (int i = 0; i < 4; i++) {
            registry.submit(BackgroundTaskRegistry.Priority.INTERACTIVE,
                    "demo", "interactive-" + i, SLOW);
        }
        BackgroundTaskRegistry.Capacity capacity = registry.capacity();
        assertEquals(4, capacity.batchQueueLimit());
        assertEquals(8, capacity.nonInteractiveQueueLimit());
        assertEquals(4, capacity.queuedInteractive());
        assertEquals(4, capacity.queuedNormal());
        assertEquals(4, capacity.queuedBatch());
        registry.list().forEach(snapshot ->
                registry.kill((String) snapshot.get("taskId")));
    }

    @Test
    void queuedTaskCanBeCancelledWithoutStartingItsBody() throws Exception {
        BackgroundTaskRegistry registry = registry(1, 1);
        BackgroundTaskRegistry.Task running = registry.submit("demo", "running", SLOW);
        await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> running.status() == BackgroundTaskRegistry.Status.RUNNING);
        AtomicInteger bodyRuns = new AtomicInteger();
        AtomicInteger cancelHooks = new AtomicInteger();
        BackgroundTaskRegistry.Task queued = registry.submit("demo", "queued", task -> {
            bodyRuns.incrementAndGet();
            return "should not run";
        });
        queued.onCancel(cancelHooks::incrementAndGet);
        await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> queued.status() == BackgroundTaskRegistry.Status.QUEUED);

        assertTrue(registry.kill(queued.id()));
        await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> queued.status() == BackgroundTaskRegistry.Status.CANCELLED);
        registry.awaitOutput(queued.id(), 5_000);
        assertEquals(0, bodyRuns.get());
        assertEquals(1, cancelHooks.get());
        assertTrue((long) registry.summary(queued).get("queueWaitMs") >= 0);
        assertFalse(registry.summary(queued).containsKey("runDurationMs"));
        assertEquals(1, registry.capacity().available());
        // Cancellation releases queue admission immediately after the task reaches terminal state.
        registry.submit("demo", "replacement", task -> "accepted");
        registry.kill(running.id());
    }

    @Test
    void cancellationRegisteredAfterARacingKillRunsImmediately() throws Exception {
        BackgroundTaskRegistry registry = registry(1, 0);
        java.util.concurrent.CountDownLatch bodyStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch attachCanceller = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger cancelHooks = new AtomicInteger();
        BackgroundTaskRegistry.Task task = registry.submit("demo", "racing-cancel", running -> {
            bodyStarted.countDown();
            attachCanceller.await();
            running.onCancel(cancelHooks::incrementAndGet);
            if (running.cancelRequested()) throw new IllegalStateException("cancelled");
            return "unexpected";
        });
        assertTrue(bodyStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));

        assertTrue(registry.kill(task.id()));
        attachCanceller.countDown();
        registry.awaitOutput(task.id(), 5_000);

        assertEquals(BackgroundTaskRegistry.Status.CANCELLED, task.status());
        assertEquals(1, cancelHooks.get());
    }

    /** 6.1: the cap must hold under racing submissions, not just sequential ones. */
    @Test
    void racingSubmissionsNeverExceedTheCap() throws Exception {
        BackgroundTaskRegistry registry = registry(4, 8);
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
            assertEquals(12, accepted, "running plus queued capacity is atomic under contention");
            registry.list().forEach(snapshot ->
                    registry.kill((String) snapshot.get("taskId")));
        }
    }

    @RepeatedTest(5)
    void racingOwnerSubmissionsLeaveCapacityForAnotherOwner() throws Exception {
        BackgroundTaskRegistry registry = registry(4, 32, 4);
        int attempts = 64;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Boolean>> results = new java.util.ArrayList<>();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(attempts)) {
            for (int i = 0; i < attempts; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        registry.submit(1L, "demo", "owner-race", SLOW);
                        return true;
                    } catch (BackgroundTaskCapacityException capped) {
                        assertEquals("owner", capped.capacityScope());
                        return false;
                    }
                }));
            }
            start.countDown();
            int accepted = 0;
            for (var future : results) accepted += future.get() ? 1 : 0;
            assertEquals(8, accepted,
                    "one owner may fill execution slots plus only its own queue share");

            BackgroundTaskRegistry.Task other = registry.submit(
                    2L, "demo", "other-owner", task -> "accepted");
            registry.list().forEach(snapshot ->
                    registry.kill((String) snapshot.get("taskId")));
            await().atMost(java.time.Duration.ofSeconds(5))
                    .until(() -> other.status() == BackgroundTaskRegistry.Status.COMPLETED);
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

    @Test
    void taskVisibilityAndControlAreOwnerScoped() throws Exception {
        BackgroundTaskRepository repository = mock(BackgroundTaskRepository.class);
        when(repository.save(any(BackgroundTaskEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findByStatusInOrderByCreatedAtAsc(List.of("QUEUED", "RUNNING")))
                .thenReturn(List.of());
        when(repository.findByStatusNotInOrderByCreatedAtDesc(List.of("QUEUED", "RUNNING")))
                .thenReturn(List.of());
        AtomicLong userId = new AtomicLong(1L);
        SecurityContext security = mock(SecurityContext.class);
        when(security.currentUserId()).thenAnswer(ignored -> userId.get());
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry(repository, security);

        BackgroundTaskRegistry.Task owned = registry.submit("demo", "private", running -> "secret");
        registry.awaitOutput(owned.id(), 5_000);
        assertEquals(1, registry.list().size());

        userId.set(2L);
        assertEquals(List.of(), registry.list());
        assertEquals(null, registry.awaitOutput(owned.id(), 0));
        assertFalse(registry.kill(owned.id()));
        assertFalse(registry.delete(owned.id()));

        userId.set(1L);
        assertEquals("secret", registry.awaitOutput(owned.id(), 0).get("output"));
    }

    private static BackgroundTaskRegistry registry(int running, int queued) {
        return new BackgroundTaskRegistry(null, new NoopSecurityContext(), running, queued);
    }

    private static BackgroundTaskRegistry registry(int running, int queued, int ownerQueued) {
        return new BackgroundTaskRegistry(
                null, new NoopSecurityContext(), running, queued, ownerQueued);
    }

    private static BackgroundTaskRegistry registry(int running, int queued, int ownerQueued,
                                                     int batchQueued, int nonInteractiveQueued) {
        return new BackgroundTaskRegistry(null, new NoopSecurityContext(), running, queued,
                ownerQueued, batchQueued, nonInteractiveQueued);
    }

    private static BackgroundTaskRegistry registry(int running, int queued, int ownerQueued,
                                                     int ownerBatchQueued,
                                                     int ownerNonInteractiveQueued,
                                                     int globalBatchQueued,
                                                     int globalNonInteractiveQueued) {
        return new BackgroundTaskRegistry(null, new NoopSecurityContext(), running, queued,
                ownerQueued, ownerBatchQueued, ownerNonInteractiveQueued,
                globalBatchQueued, globalNonInteractiveQueued);
    }
}
