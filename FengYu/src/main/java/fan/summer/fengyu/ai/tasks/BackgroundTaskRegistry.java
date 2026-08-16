package fan.summer.fengyu.ai.tasks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Host-level background-task registry — the agent-facing counterpart of terminal-agent
 * task models: submit returns a {@code taskId} immediately, the caller can poll or block
 * for output, wait on many tasks at once ({@code any}/{@code all}), and kill a task with
 * a graceful-first escalation (cooperative cancel flag → caller-supplied canceller →, for
 * process-backed tasks, SIGTERM then SIGKILL).
 *
 * <p>One registry serves every producer (workflow runs launched by the model, long plugin
 * jobs), so the {@code task_output}/wait/kill tool surface is uniform.
 */
@Service
public class BackgroundTaskRegistry {

    private static final Logger log = LoggerFactory.getLogger(BackgroundTaskRegistry.class);
    private static final int MAX_COMPLETED_RETAINED = 100;
    private static final int MAX_OUTPUT_CHARS = 100_000;
    private static final int MAX_CONCURRENT = 16;

    public enum Status { RUNNING, COMPLETED, FAILED, CANCELLED }

    /** A live or finished task. */
    public static final class Task {
        final String id;
        final String kind;
        final String description;
        final Instant createdAt = Instant.now();
        final AtomicBoolean cancelRequested = new AtomicBoolean();
        final CountDownLatch done = new CountDownLatch(1);
        final Map<String, Object> metadata = new LinkedHashMap<>();
        volatile Status status = Status.RUNNING;
        volatile String output = "";
        /** Optional aggressive canceller (e.g. mark the underlying agent run cancelled). */
        volatile Runnable canceller;
        /** Optional live process for SIGTERM → SIGKILL escalation. */
        volatile Supplier<ProcessHandle> process;

        Task(String id, String kind, String description) {
            this.id = id;
            this.kind = kind;
            this.description = description;
        }

        public String id() { return id; }
        public String kind() { return kind; }
        public String description() { return description; }
        public Status status() { return status; }
        public String output() { return output; }
        public Instant createdAt() { return createdAt; }
        public boolean cancelRequested() { return cancelRequested.get(); }
    }

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    /** Hard concurrent-running cap: acquired at submit, released at every terminal state. */
    private final java.util.concurrent.Semaphore runningSlots =
            new java.util.concurrent.Semaphore(MAX_CONCURRENT);

    /**
     * Runs {@code body} on a virtual thread and returns its task immediately. The body
     * receives the task so it can honor {@link Task#cancelRequested()} cooperatively and
     * attach a canceller/process. The concurrent cap is a semaphore — an atomic
     * check-then-count would let racing submissions all pass the limit (6.1).
     */
    public Task submit(String kind, String description, TaskBody body) {
        if (!runningSlots.tryAcquire()) {
            throw new IllegalStateException("Too many concurrent background tasks ("
                    + MAX_CONCURRENT + "); wait for existing tasks to finish");
        }
        Task task = new Task(java.util.UUID.randomUUID().toString(), kind, description);
        tasks.put(task.id, task);
        Thread.ofVirtual().name("bg-task-" + task.id).start(() -> {
            try {
                String result = body.run(task);
                task.output = truncate(result);
                task.status = Status.COMPLETED;
            } catch (Exception e) {
                if (task.cancelRequested.get()) {
                    task.status = Status.CANCELLED;
                    task.output = truncate(e.getMessage() == null ? "cancelled" : e.getMessage());
                } else {
                    task.status = Status.FAILED;
                    task.output = truncate(e.getMessage() == null
                            ? e.getClass().getSimpleName() : e.getMessage());
                }
                log.debug("background task {} ended {}: {}", task.id, task.status, task.output);
            } finally {
                task.done.countDown();
                runningSlots.release();
                evictOldCompleted();
            }
        });
        return task;
    }

    /** The task snapshot, or null for an unknown id. */
    public Task get(String taskId) {
        return tasks.get(taskId);
    }

    /** Summary of every live task (and recently finished ones) newest-first. */
    public List<Map<String, Object>> list() {
        List<Task> ordered = new ArrayList<>(tasks.values());
        ordered.sort((a, b) -> b.createdAt.compareTo(a.createdAt));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Task task : ordered) {
            out.add(summary(task));
        }
        return out;
    }

    /**
     * Blocks up to {@code timeoutMillis} for the task to finish; returns its snapshot
     * (with whatever output exists so far when the timeout elapses).
     */
    public Map<String, Object> awaitOutput(String taskId, long timeoutMillis) throws InterruptedException {
        Task task = tasks.get(taskId);
        if (task == null) return null;
        if (timeoutMillis > 0) {
            task.done.await(Math.min(timeoutMillis, 60_000), TimeUnit.MILLISECONDS);
        }
        return summary(task);
    }

    public enum WaitMode { ANY, ALL }

    /**
     * Waits on up to 20 tasks at once ({@code any} returns when the first finishes,
     * {@code all} when every task has). Returns every task's snapshot.
     */
    public List<Map<String, Object>> waitMany(List<String> taskIds, WaitMode mode,
                                              long timeoutMillis) throws InterruptedException {
        if (taskIds == null || taskIds.isEmpty()) return List.of();
        if (taskIds.size() > 20) {
            throw new IllegalArgumentException("wait_tasks accepts at most 20 task ids");
        }
        List<Task> targets = new ArrayList<>();
        for (String id : taskIds) {
            Task task = tasks.get(id);
            if (task != null) targets.add(task);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                Math.min(Math.max(timeoutMillis, 0), 120_000));
        while (System.nanoTime() < deadline) {
            long finished = targets.stream().filter(t -> t.done.getCount() == 0).count();
            if ((mode == WaitMode.ANY && finished >= 1) || (mode == WaitMode.ALL && finished == targets.size())) {
                break;
            }
            Thread.sleep(50);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Task task : targets) out.add(summary(task));
        return out;
    }

    /**
     * Kills a task cooperatively first (cancel flag + canceller), escalating for
     * process-backed tasks: SIGTERM, a 2s grace, then SIGKILL. Returns false when the
     * task is unknown or already finished.
     */
    public boolean kill(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null || task.done.getCount() == 0) return false;
        task.cancelRequested.set(true);
        Runnable canceller = task.canceller;
        if (canceller != null) {
            try {
                canceller.run();
            } catch (Exception e) {
                log.warn("task {} canceller failed: {}", taskId, e.getMessage());
            }
        }
        Supplier<ProcessHandle> process = task.process;
        if (process != null) {
            Thread.ofVirtual().start(() -> {
                try {
                    ProcessHandle handle = process.get();
                    if (handle != null && handle.isAlive()) {
                        handle.destroy();            // SIGTERM
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                        while (handle.isAlive() && System.nanoTime() < deadline) {
                            Thread.sleep(20);
                        }
                        if (handle.isAlive()) {
                            handle.destroyForcibly(); // SIGKILL escalation
                            log.info("task {} process force-killed after grace", taskId);
                        }
                    }
                } catch (Exception ignored) {
                    // Process already exited — cooperative cancellation governs.
                }
            });
        }
        return true;
    }

    /** Deletes a finished task from the registry (live tasks must be killed first). */
    public boolean delete(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null) return false;
        if (task.status == Status.RUNNING) {
            throw new IllegalStateException("Task is still running; kill it first");
        }
        tasks.remove(taskId);
        return true;
    }

    public Map<String, Object> summary(Task task) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", task.id);
        out.put("kind", task.kind);
        out.put("description", task.description);
        out.put("status", task.status.name().toLowerCase(java.util.Locale.ROOT));
        out.put("createdAt", task.createdAt.toString());
        out.put("output", task.output);
        out.put("cancelRequested", task.cancelRequested.get());
        return out;
    }

    private void evictOldCompleted() {
        List<Task> finished = tasks.values().stream()
                .filter(t -> t.status != Status.RUNNING)
                .sorted((a, b) -> b.createdAt.compareTo(a.createdAt))
                .toList();
        for (int i = MAX_COMPLETED_RETAINED; i < finished.size(); i++) {
            tasks.remove(finished.get(i).id, finished.get(i));
        }
    }

    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() <= MAX_OUTPUT_CHARS ? value
                : value.substring(0, MAX_OUTPUT_CHARS) + "…";
    }

    /** The task body; may poll {@link Task#cancelRequested()} for cooperative cancellation. */
    @FunctionalInterface
    public interface TaskBody {
        String run(Task task) throws Exception;
    }
}
