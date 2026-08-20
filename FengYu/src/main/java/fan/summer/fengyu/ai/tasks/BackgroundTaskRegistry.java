package fan.summer.fengyu.ai.tasks;

import fan.summer.fengyu.ai.metrics.BackgroundTaskMetrics;
import fan.summer.fengyu.database.entity.ai.BackgroundTaskEntity;
import fan.summer.fengyu.database.repository.ai.BackgroundTaskRepository;
import fan.summer.fengyu.security.NoopSecurityContext;
import fan.summer.fengyu.security.SecurityContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Host-level background-task registry — the agent-facing counterpart of terminal-agent
 * task models: submit returns a {@code taskId} immediately, the caller can poll or block
 * for output, wait on many tasks at once ({@code any}/{@code all}), and kill a task with
 * a graceful-first escalation (cooperative cancel flag → caller-supplied canceller →, for
 * process-backed tasks, SIGTERM then SIGKILL).
 *
 * <p>One registry serves every producer (workflow runs launched by the model, long plugin
 * jobs), so the {@code task_output}/wait/kill tool surface is uniform. Task snapshots are
 * owner-scoped and durable: recent terminal output survives restart, while work that was still
 * queued or running at shutdown is restored as failed rather than left permanently ambiguous.
 */
@Service
public class BackgroundTaskRegistry {

    private static final Logger log = LoggerFactory.getLogger(BackgroundTaskRegistry.class);
    private static final int MAX_COMPLETED_RETAINED = 100;
    private static final int MAX_OUTPUT_CHARS = 100_000;
    private static final int MAX_CONCURRENT = 16;
    private static final int MAX_QUEUED = 128;
    private static final int MAX_QUEUED_BATCH = 64;
    private static final int MAX_QUEUED_NON_INTERACTIVE = 96;
    private static final int MAX_QUEUED_PER_OWNER = 32;
    private static final int MAX_QUEUED_BATCH_PER_OWNER = 16;
    private static final int MAX_QUEUED_NON_INTERACTIVE_PER_OWNER = 24;
    private static final int RETRY_AFTER_SECONDS = 1;
    public static final String SCHEDULING_POLICY = "owner-round-robin-weighted-priority";

    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }
    public enum Priority { INTERACTIVE, NORMAL, BATCH }

    /** Four interactive turns, two normal turns, and one batch turn per owner cycle. */
    private static final Priority[] PRIORITY_CYCLE = {
            Priority.INTERACTIVE, Priority.INTERACTIVE,
            Priority.INTERACTIVE, Priority.INTERACTIVE,
            Priority.NORMAL, Priority.NORMAL, Priority.BATCH
    };

    /** A live or finished task. */
    public static final class Task {
        final String id;
        final long userId;
        final Priority priority;
        final String kind;
        final String description;
        final Instant createdAt;
        final AtomicBoolean cancelRequested = new AtomicBoolean();
        final CountDownLatch done = new CountDownLatch(1);
        final Map<String, Object> metadata = new LinkedHashMap<>();
        volatile Status status = Status.QUEUED;
        volatile String output = "";
        volatile Instant startedAt;
        volatile Instant completedAt;
        /** The virtual thread waiting for or occupying an execution slot. */
        volatile Thread workerThread;
        /** Optional aggressive canceller (e.g. mark the underlying agent run cancelled). */
        volatile Runnable canceller;
        /** Last cancellation stage delivered; distinct replacement hooks may each run once. */
        final AtomicReference<Runnable> deliveredCanceller = new AtomicReference<>();
        /** Optional live process for SIGTERM → SIGKILL escalation. */
        volatile Supplier<ProcessHandle> process;

        Task(String id, long userId, Priority priority, String kind, String description,
             Instant createdAt) {
            this.id = id;
            this.userId = userId;
            this.priority = priority;
            this.kind = kind;
            this.description = description;
            this.createdAt = createdAt;
        }

        Task(BackgroundTaskEntity entity) {
            this(entity.getId(), entity.getUserId(),
                    BackgroundTaskRegistry.priority(entity.getPriority()),
                    entity.getKind(), entity.getDescription(), entity.getCreatedAt());
            this.status = BackgroundTaskRegistry.status(entity.getStatus());
            this.output = entity.getOutput();
            this.startedAt = entity.getStartedAt();
            this.completedAt = entity.getCompletedAt();
            this.cancelRequested.set(entity.isCancelRequested());
            this.done.countDown();
        }

        public String id() { return id; }
        public Priority priority() { return priority; }
        public String kind() { return kind; }
        public String description() { return description; }
        public Status status() { return status; }
        public String output() { return output; }
        public Instant createdAt() { return createdAt; }
        public boolean cancelRequested() { return cancelRequested.get(); }
        /** Attaches the producer's cooperative cancellation bridge without losing a racing kill. */
        public void onCancel(Runnable action) {
            this.canceller = action;
            deliverCancellation();
        }

        private void deliverCancellation() {
            Runnable action = canceller;
            if (!cancelRequested.get() || action == null) return;
            while (true) {
                Runnable delivered = deliveredCanceller.get();
                if (delivered == action) return;
                if (deliveredCanceller.compareAndSet(delivered, action)) {
                    action.run();
                    return;
                }
            }
        }
    }

    /** FIFO sub-queues selected by a bounded 4:2:1 weighted cycle. */
    private static final class OwnerQueue {
        private final EnumMap<Priority, ArrayDeque<Task>> queues =
                new EnumMap<>(Priority.class);
        private int cycleCursor;

        OwnerQueue() {
            for (Priority priority : Priority.values()) {
                queues.put(priority, new ArrayDeque<>());
            }
        }

        void add(Task task) {
            queues.get(task.priority).addLast(task);
        }

        boolean remove(Task task) {
            return queues.get(task.priority).remove(task);
        }

        Task peekNext() {
            int index = nextCycleIndex();
            return index < 0 ? null : queues.get(PRIORITY_CYCLE[index]).peekFirst();
        }

        Task pollNext() {
            int index = nextCycleIndex();
            if (index < 0) return null;
            Task task = queues.get(PRIORITY_CYCLE[index]).removeFirst();
            cycleCursor = (index + 1) % PRIORITY_CYCLE.length;
            return task;
        }

        private int nextCycleIndex() {
            for (int offset = 0; offset < PRIORITY_CYCLE.length; offset++) {
                int index = (cycleCursor + offset) % PRIORITY_CYCLE.length;
                if (!queues.get(PRIORITY_CYCLE[index]).isEmpty()) return index;
            }
            return -1;
        }

        int size() {
            return queues.values().stream().mapToInt(ArrayDeque::size).sum();
        }

        int size(Priority priority) {
            return queues.get(priority).size();
        }

        boolean isEmpty() {
            return queues.values().stream().allMatch(ArrayDeque::isEmpty);
        }

        Iterable<Task> tasks(Priority priority) {
            return queues.get(priority);
        }
    }

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final BackgroundTaskRepository repository;
    private final SecurityContext securityContext;
    /** Optional queueing metrics; null in tests keeps the registry side-effect free. */
    private final BackgroundTaskMetrics metrics;
    private final int maxConcurrent;
    private final int maxQueued;
    private final int maxQueuedBatch;
    private final int maxQueuedNonInteractive;
    private final int maxQueuedPerOwner;
    private final int maxQueuedBatchPerOwner;
    private final int maxQueuedNonInteractivePerOwner;
    /** Execution slots are work-conserving; explicit owner rotation decides who acquires next. */
    private final Semaphore runningSlots;
    /** Bounds running + queued work so a producer cannot grow the virtual-thread queue forever. */
    private final Semaphore admissionSlots;
    /**
     * Virtual owner queues are round-robin across owners and 4:2:1 weighted across interactive,
     * normal, and batch work within an owner. Each owner-priority sub-queue remains FIFO. An owner
     * may use every idle slot when alone, but cannot monopolize newly released slots while another
     * owner has admitted work waiting; the batch turn bounds starvation under interactive load.
     */
    private final Object schedulingMonitor = new Object();
    private final Map<Long, OwnerQueue> queuedByOwner = new LinkedHashMap<>();
    private final Map<Long, Integer> runningByOwner = new LinkedHashMap<>();
    private final ArrayDeque<Long> ownerRotation = new ArrayDeque<>();

    /** In-memory constructor for focused tests. */
    public BackgroundTaskRegistry() {
        this(null, new NoopSecurityContext(), MAX_CONCURRENT, MAX_QUEUED,
                MAX_QUEUED_PER_OWNER, MAX_QUEUED_BATCH_PER_OWNER,
                MAX_QUEUED_NON_INTERACTIVE_PER_OWNER, MAX_QUEUED_BATCH,
                MAX_QUEUED_NON_INTERACTIVE);
    }

    /** Registry-backed constructor without metrics, used by persistence tests. */
    public BackgroundTaskRegistry(BackgroundTaskRepository repository,
                                  SecurityContext securityContext) {
        this(repository, securityContext, null);
    }

    @Autowired
    public BackgroundTaskRegistry(BackgroundTaskRepository repository,
                                  SecurityContext securityContext,
                                  ObjectProvider<BackgroundTaskMetrics> metricsProvider) {
        this(repository, securityContext, MAX_CONCURRENT, MAX_QUEUED,
                MAX_QUEUED_PER_OWNER, MAX_QUEUED_BATCH_PER_OWNER,
                MAX_QUEUED_NON_INTERACTIVE_PER_OWNER, MAX_QUEUED_BATCH,
                MAX_QUEUED_NON_INTERACTIVE,
                metricsProvider == null ? null : metricsProvider.getIfAvailable());
    }

    /** Capacity-injected constructor for deterministic queue tests. */
    BackgroundTaskRegistry(BackgroundTaskRepository repository, SecurityContext securityContext,
                           int maxConcurrent, int maxQueued) {
        this(repository, securityContext, maxConcurrent, maxQueued, maxQueued,
                maxQueued, maxQueued, maxQueued, maxQueued);
    }

    /** Capacity- and owner-limit-injected constructor for deterministic fairness tests. */
    BackgroundTaskRegistry(BackgroundTaskRepository repository, SecurityContext securityContext,
                           int maxConcurrent, int maxQueued, int maxQueuedPerOwner) {
        this(repository, securityContext, maxConcurrent, maxQueued, maxQueuedPerOwner,
                maxQueuedPerOwner, maxQueuedPerOwner, maxQueued, maxQueued);
    }

    /** Fully injected constructor for deterministic priority-reservation tests. */
    BackgroundTaskRegistry(BackgroundTaskRepository repository, SecurityContext securityContext,
                           int maxConcurrent, int maxQueued, int maxQueuedPerOwner,
                           int maxQueuedBatchPerOwner,
                           int maxQueuedNonInteractivePerOwner) {
        this(repository, securityContext, maxConcurrent, maxQueued, maxQueuedPerOwner,
                maxQueuedBatchPerOwner, maxQueuedNonInteractivePerOwner,
                maxQueued, maxQueued);
    }

    /** Fully injected constructor including global and owner priority reservations. */
    BackgroundTaskRegistry(BackgroundTaskRepository repository, SecurityContext securityContext,
                           int maxConcurrent, int maxQueued, int maxQueuedPerOwner,
                           int maxQueuedBatchPerOwner, int maxQueuedNonInteractivePerOwner,
                           int maxQueuedBatch, int maxQueuedNonInteractive) {
        this(repository, securityContext, maxConcurrent, maxQueued, maxQueuedPerOwner,
                maxQueuedBatchPerOwner, maxQueuedNonInteractivePerOwner,
                maxQueuedBatch, maxQueuedNonInteractive, null);
    }

    /** Fully injected constructor including queueing metrics. */
    BackgroundTaskRegistry(BackgroundTaskRepository repository, SecurityContext securityContext,
                           int maxConcurrent, int maxQueued, int maxQueuedPerOwner,
                           int maxQueuedBatchPerOwner, int maxQueuedNonInteractivePerOwner,
                           int maxQueuedBatch, int maxQueuedNonInteractive,
                           BackgroundTaskMetrics metrics) {
        if (maxConcurrent < 1 || maxQueued < 0 || maxQueuedPerOwner < 0
                || maxQueuedPerOwner > maxQueued || maxQueuedBatchPerOwner < 0
                || maxQueuedBatchPerOwner > maxQueuedNonInteractivePerOwner
                || maxQueuedNonInteractivePerOwner > maxQueuedPerOwner
                || maxQueuedBatch < 0 || maxQueuedBatch > maxQueuedNonInteractive
                || maxQueuedNonInteractive > maxQueued) {
            throw new IllegalArgumentException("Invalid background task capacity");
        }
        this.repository = repository;
        this.securityContext = securityContext;
        this.metrics = metrics;
        this.maxConcurrent = maxConcurrent;
        this.maxQueued = maxQueued;
        this.maxQueuedBatch = maxQueuedBatch;
        this.maxQueuedNonInteractive = maxQueuedNonInteractive;
        this.maxQueuedPerOwner = maxQueuedPerOwner;
        this.maxQueuedBatchPerOwner = maxQueuedBatchPerOwner;
        this.maxQueuedNonInteractivePerOwner = maxQueuedNonInteractivePerOwner;
        this.runningSlots = new Semaphore(maxConcurrent, true);
        this.admissionSlots = new Semaphore(Math.addExact(maxConcurrent, maxQueued), true);
        if (metrics != null) {
            for (Priority priority : Priority.values()) {
                String tag = priority.name().toLowerCase(java.util.Locale.ROOT);
                metrics.bindQueueState(tag,
                        () -> queuedCountForMetrics(priority),
                        () -> oldestQueuedWaitMsForMetrics(priority));
            }
        }
    }

    /** Restores recent history and resolves tasks interrupted by an application restart. */
    @PostConstruct
    void recoverTasks() {
        if (repository == null) return;
        Instant now = Instant.now();
        List<BackgroundTaskEntity> interrupted = repository.findByStatusInOrderByCreatedAtAsc(
                List.of(Status.QUEUED.name(), Status.RUNNING.name()));
        for (BackgroundTaskEntity entity : interrupted) {
            if (Status.RUNNING.name().equals(entity.getStatus()) && entity.getStartedAt() == null) {
                // Older durable rows predate startedAt; a running task had started no later than
                // its creation. Queued rows intentionally remain null because their body never ran.
                entity.setStartedAt(entity.getCreatedAt());
            }
            entity.setStatus(Status.FAILED.name());
            entity.setOutput("Queued or running task interrupted by application restart; "
                    + "it was not replayed to avoid duplicate side effects.");
            entity.setCompletedAt(now);
        }
        if (!interrupted.isEmpty()) {
            repository.saveAll(interrupted);
            log.warn("restored {} interrupted background task(s) as failed", interrupted.size());
        }
        List<BackgroundTaskEntity> finished = repository.findByStatusNotInOrderByCreatedAtDesc(
                List.of(Status.QUEUED.name(), Status.RUNNING.name()));
        for (int i = 0; i < finished.size(); i++) {
            BackgroundTaskEntity entity = finished.get(i);
            if (i < MAX_COMPLETED_RETAINED) {
                tasks.put(entity.getId(), new Task(entity));
            } else {
                repository.delete(entity);
            }
        }
    }

    /**
     * Runs {@code body} on a virtual thread and returns its task immediately. The body
     * receives the task so it can honor {@link Task#cancelRequested()} cooperatively and
     * attach a canceller/process. At most {@value #MAX_CONCURRENT} bodies run at once; another
     * {@value #MAX_QUEUED} wait in a bounded queue, with at most
     * {@value #MAX_QUEUED_PER_OWNER} queued for one owner. Global and per-owner reservations retain
     * admission room for higher-priority work. Queued work is FIFO within each owner-priority
     * sub-queue, selected 4:2:1 for interactive, normal, and batch work, while owners take turns.
     * Queue admission remains atomic under racing submissions and queued tasks can be cancelled
     * before their body starts.
     */
    public Task submit(String kind, String description, TaskBody body) {
        return submit(currentUserId(), Priority.NORMAL, kind, description, body);
    }

    /** Submits for the current owner at an explicit workload priority. */
    public Task submit(Priority priority, String kind, String description, TaskBody body) {
        return submit(currentUserId(), priority, kind, description, body);
    }

    /** Submits for an explicit owner, used by durable schedules firing off-request. */
    public Task submit(long userId, String kind, String description, TaskBody body) {
        return submit(userId, Priority.NORMAL, kind, description, body);
    }

    /** Submits for an explicit owner and workload priority. */
    public Task submit(long userId, Priority priority, String kind, String description,
                       TaskBody body) {
        if (priority == null) throw new IllegalArgumentException("Task priority is required");
        Task task = new Task(java.util.UUID.randomUUID().toString(), userId, priority,
                kind, description, Instant.now());
        boolean runningSlotReserved;
        try {
            runningSlotReserved = admitToScheduler(task);
        } catch (BackgroundTaskCapacityException capacity) {
            if (metrics != null) {
                metrics.rejected(priority.name().toLowerCase(java.util.Locale.ROOT),
                        capacity.capacityScope());
            }
            throw capacity;
        }
        tasks.put(task.id, task);
        try {
            persist(task);
        } catch (RuntimeException persistenceFailure) {
            tasks.remove(task.id, task);
            undoSchedulerAdmission(task, runningSlotReserved);
            admissionSlots.release();
            throw persistenceFailure;
        }
        Thread worker = Thread.ofVirtual().name("bg-task-" + task.id).unstarted(() -> {
            boolean runningSlotAcquired = runningSlotReserved;
            try {
                if (!runningSlotReserved) {
                    awaitExecutionTurn(task);
                    runningSlotAcquired = true;
                }
                if (task.cancelRequested.get()) {
                    throw new InterruptedException("cancelled while queued");
                }
                task.startedAt = Instant.now();
                task.status = Status.RUNNING;
                if (metrics != null) {
                    metrics.dispatched(task.priority.name().toLowerCase(java.util.Locale.ROOT),
                            Duration.between(task.createdAt, task.startedAt));
                }
                persist(task);
                if (task.cancelRequested.get()) {
                    throw new InterruptedException("cancelled before execution");
                }
                String result = body.run(task);
                if (task.cancelRequested.get()) {
                    task.output = "cancelled";
                    task.status = Status.CANCELLED;
                } else {
                    task.output = truncate(result);
                    task.status = Status.COMPLETED;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                task.status = task.cancelRequested.get() ? Status.CANCELLED : Status.FAILED;
                task.output = task.cancelRequested.get()
                        ? "cancelled while queued" : "Task worker interrupted";
                if (metrics != null && task.startedAt == null && task.cancelRequested.get()) {
                    metrics.cancelledWhileQueued(
                            task.priority.name().toLowerCase(java.util.Locale.ROOT),
                            Duration.between(task.createdAt, Instant.now()));
                }
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
                task.completedAt = Instant.now();
                try {
                    persist(task);
                } catch (RuntimeException persistenceFailure) {
                    log.warn("background task {} completion not persisted: {}",
                            task.id, persistenceFailure.getMessage());
                }
                task.done.countDown();
                if (runningSlotAcquired) {
                    releaseRunningSlot(task);
                }
                admissionSlots.release();
                evictOldCompleted();
            }
        });
        task.workerThread = worker;
        try {
            worker.start();
        } catch (RuntimeException | Error startFailure) {
            undoSchedulerAdmission(task, runningSlotReserved);
            tasks.remove(task.id, task);
            try {
                if (repository != null) repository.deleteById(task.id);
            } catch (RuntimeException cleanupFailure) {
                startFailure.addSuppressed(cleanupFailure);
            } finally {
                admissionSlots.release();
            }
            throw startFailure;
        }
        return task;
    }

    /**
     * Reserves an immediately available execution slot, or atomically appends to the owner's
     * bounded virtual queue. Returns true when the execution slot was reserved synchronously.
     */
    private boolean admitToScheduler(Task task) {
        synchronized (schedulingMonitor) {
            boolean canRunImmediately = ownerRotation.isEmpty()
                    && runningSlots.availablePermits() > 0;
            OwnerQueue ownerQueue = queuedByOwner.get(task.userId);
            if (admissionSlots.availablePermits() == 0) {
                throw new BackgroundTaskCapacityException(
                        maxConcurrent, maxQueued, RETRY_AFTER_SECONDS);
            }
            if (!canRunImmediately && queuedCountLocked() >= maxQueued) {
                throw new BackgroundTaskCapacityException(
                        maxConcurrent, maxQueued, RETRY_AFTER_SECONDS);
            }
            if (!canRunImmediately
                    && ((ownerQueue != null && ownerQueue.size() >= maxQueuedPerOwner)
                    || (ownerQueue == null && maxQueuedPerOwner == 0))) {
                throw new BackgroundTaskCapacityException(
                        maxQueuedPerOwner, RETRY_AFTER_SECONDS);
            }
            if (!canRunImmediately) {
                int batchQueued = ownerQueue == null ? 0
                        : ownerQueue.size(Priority.BATCH);
                int normalQueued = ownerQueue == null ? 0
                        : ownerQueue.size(Priority.NORMAL);
                if (task.priority == Priority.BATCH
                        && batchQueued >= maxQueuedBatchPerOwner) {
                    throw new BackgroundTaskCapacityException("batch",
                            maxQueuedBatchPerOwner, RETRY_AFTER_SECONDS);
                }
                if (task.priority == Priority.BATCH
                        && batchQueued + normalQueued >= maxQueuedNonInteractivePerOwner) {
                    throw new BackgroundTaskCapacityException("batch",
                            maxQueuedNonInteractivePerOwner, RETRY_AFTER_SECONDS);
                }
                if (task.priority == Priority.NORMAL
                        && batchQueued + normalQueued >= maxQueuedNonInteractivePerOwner) {
                    throw new BackgroundTaskCapacityException("normal",
                            maxQueuedNonInteractivePerOwner, RETRY_AFTER_SECONDS);
                }
                int globallyQueuedBatch = queuedCountLocked(Priority.BATCH);
                int globallyQueuedNormal = queuedCountLocked(Priority.NORMAL);
                if (task.priority == Priority.BATCH
                        && globallyQueuedBatch >= maxQueuedBatch) {
                    throw BackgroundTaskCapacityException.globalPriority(
                            "batch", maxQueuedBatch, RETRY_AFTER_SECONDS);
                }
                if (task.priority == Priority.BATCH
                        && globallyQueuedBatch + globallyQueuedNormal
                        >= maxQueuedNonInteractive) {
                    throw BackgroundTaskCapacityException.globalPriority(
                            "batch", maxQueuedNonInteractive, RETRY_AFTER_SECONDS);
                }
                if (task.priority == Priority.NORMAL
                        && globallyQueuedBatch + globallyQueuedNormal
                        >= maxQueuedNonInteractive) {
                    throw BackgroundTaskCapacityException.globalPriority(
                            "normal", maxQueuedNonInteractive, RETRY_AFTER_SECONDS);
                }
            }
            if (!admissionSlots.tryAcquire()) {
                throw new IllegalStateException("Background task scheduler lost admission capacity");
            }
            if (canRunImmediately) {
                // Only this monitor acquires running slots, so the availability checked above
                // cannot be consumed between the check and reservation.
                if (!runningSlots.tryAcquire()) {
                    admissionSlots.release();
                    throw new IllegalStateException("Background task scheduler lost a running slot");
                }
                runningByOwner.merge(task.userId, 1, Integer::sum);
                return true;
            }
            if (ownerQueue == null) {
                ownerQueue = new OwnerQueue();
                queuedByOwner.put(task.userId, ownerQueue);
                ownerRotation.addLast(task.userId);
            }
            ownerQueue.add(task);
            schedulingMonitor.notifyAll();
            return false;
        }
    }

    private void undoSchedulerAdmission(Task task, boolean runningSlotReserved) {
        if (runningSlotReserved) {
            releaseRunningSlot(task);
        } else {
            removeQueued(task);
        }
    }

    private int queuedCountLocked(Priority priority) {
        return queuedByOwner.values().stream()
                .mapToInt(queue -> queue.size(priority))
                .sum();
    }

    private int queuedCountLocked() {
        return queuedByOwner.values().stream()
                .mapToInt(OwnerQueue::size)
                .sum();
    }

    private Instant oldestQueuedAtLocked(Priority priority) {
        Instant oldest = null;
        for (OwnerQueue ownerQueue : queuedByOwner.values()) {
            for (Task task : ownerQueue.tasks(priority)) {
                if (oldest == null || task.createdAt.isBefore(oldest)) oldest = task.createdAt;
            }
        }
        return oldest;
    }

    /** Gauge supplier: queue depth for one priority, safe to poll on scrape. */
    private int queuedCountForMetrics(Priority priority) {
        synchronized (schedulingMonitor) {
            return queuedCountLocked(priority);
        }
    }

    /** Gauge supplier: age of the oldest queued task in one priority. */
    private long oldestQueuedWaitMsForMetrics(Priority priority) {
        synchronized (schedulingMonitor) {
            return queueWaitMs(oldestQueuedAtLocked(priority));
        }
    }

    /** Waits until this task is its owner's weighted-priority choice and round-robin turn. */
    private void awaitExecutionTurn(Task task) throws InterruptedException {
        synchronized (schedulingMonitor) {
            try {
                while (true) {
                    if (task.cancelRequested.get()) {
                        removeQueuedLocked(task);
                        throw new InterruptedException("cancelled while queued");
                    }
                    OwnerQueue ownerQueue = queuedByOwner.get(task.userId);
                    boolean nextOwner = !ownerRotation.isEmpty()
                            && ownerRotation.getFirst() == task.userId;
                    boolean ownerHead = ownerQueue != null && ownerQueue.peekNext() == task;
                    if (nextOwner && ownerHead && runningSlots.tryAcquire()) {
                        runningByOwner.merge(task.userId, 1, Integer::sum);
                        Task selected = ownerQueue.pollNext();
                        if (selected != task) {
                            runningByOwner.computeIfPresent(task.userId,
                                    (ignored, running) -> running == 1 ? null : running - 1);
                            runningSlots.release();
                            throw new IllegalStateException(
                                    "Background task priority selection changed unexpectedly");
                        }
                        ownerRotation.removeFirst();
                        if (ownerQueue.isEmpty()) {
                            queuedByOwner.remove(task.userId);
                        } else {
                            ownerRotation.addLast(task.userId);
                        }
                        schedulingMonitor.notifyAll();
                        return;
                    }
                    schedulingMonitor.wait();
                }
            } catch (InterruptedException interrupted) {
                removeQueuedLocked(task);
                schedulingMonitor.notifyAll();
                throw interrupted;
            }
        }
    }

    private void removeQueued(Task task) {
        synchronized (schedulingMonitor) {
            removeQueuedLocked(task);
            schedulingMonitor.notifyAll();
        }
    }

    private void removeQueuedLocked(Task task) {
        OwnerQueue ownerQueue = queuedByOwner.get(task.userId);
        if (ownerQueue == null || !ownerQueue.remove(task)) return;
        if (ownerQueue.isEmpty()) {
            queuedByOwner.remove(task.userId);
            ownerRotation.remove(task.userId);
        }
    }

    private void releaseRunningSlot(Task task) {
        synchronized (schedulingMonitor) {
            Integer ownerRunning = runningByOwner.get(task.userId);
            if (ownerRunning == null || ownerRunning < 1) {
                log.error("background task {} released an untracked running slot", task.id);
            } else if (ownerRunning == 1) {
                runningByOwner.remove(task.userId);
            } else {
                runningByOwner.put(task.userId, ownerRunning - 1);
            }
            runningSlots.release();
            schedulingMonitor.notifyAll();
        }
    }

    /** The task snapshot, or null for an unknown id. */
    public Task get(String taskId) {
        Task task = tasks.get(taskId);
        return task != null && task.userId == currentUserId() ? task : null;
    }

    /** Summary of every live task (and recently finished ones) newest-first. */
    public List<Map<String, Object>> list() {
        long userId = currentUserId();
        List<Task> ordered = tasks.values().stream()
                .filter(task -> task.userId == userId)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        ordered.sort((a, b) -> b.createdAt.compareTo(a.createdAt));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Task task : ordered) {
            out.add(summary(task));
        }
        return out;
    }

    /** Global queue pressure plus the current owner's share; no task details cross owners. */
    public Capacity capacity() {
        long userId = currentUserId();
        synchronized (schedulingMonitor) {
            int running = runningByOwner.values().stream().mapToInt(Integer::intValue).sum();
            int queued = 0;
            LinkedHashSet<Long> activeOwners = new LinkedHashSet<>(runningByOwner.keySet());
            int queuedInteractive = 0;
            int queuedNormal = 0;
            int queuedBatch = 0;
            for (Map.Entry<Long, OwnerQueue> entry : queuedByOwner.entrySet()) {
                activeOwners.add(entry.getKey());
                queued += entry.getValue().size();
                queuedInteractive += entry.getValue().size(Priority.INTERACTIVE);
                queuedNormal += entry.getValue().size(Priority.NORMAL);
                queuedBatch += entry.getValue().size(Priority.BATCH);
            }
            Instant oldestInteractive = oldestQueuedAtLocked(Priority.INTERACTIVE);
            Instant oldestNormal = oldestQueuedAtLocked(Priority.NORMAL);
            Instant oldestBatch = oldestQueuedAtLocked(Priority.BATCH);
            Instant oldestQueuedAt = oldestOf(oldestInteractive, oldestOf(oldestNormal, oldestBatch));
            int ownedRunning = runningByOwner.getOrDefault(userId, 0);
            OwnerQueue ownerQueue = queuedByOwner.get(userId);
            int ownedQueued = ownerQueue == null ? 0 : ownerQueue.size();
            int ownedQueuedInteractive = ownerQueue == null ? 0
                    : ownerQueue.size(Priority.INTERACTIVE);
            int ownedQueuedNormal = ownerQueue == null ? 0
                    : ownerQueue.size(Priority.NORMAL);
            int ownedQueuedBatch = ownerQueue == null ? 0
                    : ownerQueue.size(Priority.BATCH);
            int admittedAvailable = admissionSlots.availablePermits();
            int available = ownerRotation.isEmpty() ? admittedAvailable
                    : Math.min(admittedAvailable, Math.max(0, maxQueued - queued));
            int ownedQueueAvailable = Math.max(0, maxQueuedPerOwner - ownedQueued);
            return new Capacity(running, queued, maxConcurrent, maxQueued,
                    available, ownedRunning, ownedQueued, maxQueuedPerOwner,
                    ownedQueueAvailable, ownedQueueAvailable == 0,
                    maxQueuedBatch, maxQueuedNonInteractive,
                    maxQueuedBatchPerOwner, maxQueuedNonInteractivePerOwner,
                    queuedInteractive, queuedNormal, queuedBatch,
                    ownedQueuedInteractive, ownedQueuedNormal, ownedQueuedBatch,
                    activeOwners.size(),
                    queueWaitMs(oldestQueuedAt),
                    queueWaitMs(oldestInteractive),
                    queueWaitMs(oldestNormal),
                    queueWaitMs(oldestBatch),
                    available == 0 || queued >= maxQueued, SCHEDULING_POLICY);
        }
    }

    private static long queueWaitMs(Instant oldest) {
        return oldest == null ? 0 : elapsedMillis(oldest, Instant.now());
    }

    private static Instant oldestOf(Instant left, Instant right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isBefore(right) ? left : right;
    }

    /**
     * Blocks up to {@code timeoutMillis} for the task to finish; returns its snapshot
     * (with whatever output exists so far when the timeout elapses).
     */
    public Map<String, Object> awaitOutput(String taskId, long timeoutMillis) throws InterruptedException {
        Task task = get(taskId);
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
            Task task = get(id);
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
     * Cancels a queued task without starting its body, or kills a running task cooperatively
     * first (cancel flag + canceller), escalating for
     * process-backed tasks: SIGTERM, a 2s grace, then SIGKILL. Returns false when the
     * task is unknown or already finished.
     */
    public boolean kill(String taskId) {
        Task task = get(taskId);
        if (task == null || task.done.getCount() == 0) return false;
        task.cancelRequested.set(true);
        try {
            persist(task);
        } catch (RuntimeException persistenceFailure) {
            log.warn("task {} cancellation request not persisted: {}",
                    taskId, persistenceFailure.getMessage());
        }
        try {
            task.deliverCancellation();
        } catch (Exception e) {
            log.warn("task {} canceller failed: {}", taskId, e.getMessage());
        }
        if (task.status == Status.QUEUED) {
            Thread worker = task.workerThread;
            if (worker != null) worker.interrupt();
            return true;
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
        Task task = get(taskId);
        if (task == null) return false;
        if (task.status == Status.QUEUED || task.status == Status.RUNNING) {
            throw new IllegalStateException("Task is still active; kill it first");
        }
        tasks.remove(taskId);
        if (repository != null) repository.deleteById(taskId);
        return true;
    }

    public Map<String, Object> summary(Task task) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", task.id);
        out.put("priority", task.priority.name().toLowerCase(java.util.Locale.ROOT));
        out.put("kind", task.kind);
        out.put("description", task.description);
        out.put("status", task.status.name().toLowerCase(java.util.Locale.ROOT));
        out.put("createdAt", task.createdAt.toString());
        if (task.startedAt != null) out.put("startedAt", task.startedAt.toString());
        Instant queueEnd = task.startedAt != null ? task.startedAt
                : task.completedAt != null ? task.completedAt
                : task.status == Status.QUEUED ? Instant.now() : null;
        if (queueEnd != null) {
            out.put("queueWaitMs", elapsedMillis(task.createdAt, queueEnd));
        }
        if (task.startedAt != null) {
            out.put("runDurationMs", elapsedMillis(task.startedAt,
                    task.completedAt == null ? Instant.now() : task.completedAt));
        }
        out.put("output", task.output);
        out.put("cancelRequested", task.cancelRequested.get());
        if (task.completedAt != null) out.put("completedAt", task.completedAt.toString());
        return out;
    }

    private void evictOldCompleted() {
        List<Task> finished = tasks.values().stream()
                .filter(t -> t.status != Status.QUEUED && t.status != Status.RUNNING)
                .sorted((a, b) -> b.createdAt.compareTo(a.createdAt))
                .toList();
        for (int i = MAX_COMPLETED_RETAINED; i < finished.size(); i++) {
            Task evicted = finished.get(i);
            if (tasks.remove(evicted.id, evicted) && repository != null) {
                repository.deleteById(evicted.id);
            }
        }
    }

    private void persist(Task task) {
        if (repository == null) return;
        BackgroundTaskEntity entity = new BackgroundTaskEntity();
        entity.setId(task.id);
        entity.setUserId(task.userId);
        entity.setKind(task.kind);
        entity.setPriority(task.priority.name());
        entity.setDescription(task.description);
        entity.setStatus(task.status.name());
        entity.setOutput(task.output);
        entity.setCancelRequested(task.cancelRequested.get());
        entity.setCreatedAt(task.createdAt);
        entity.setStartedAt(task.startedAt);
        entity.setCompletedAt(task.completedAt);
        repository.save(entity);
    }

    private long currentUserId() {
        Long userId = securityContext.currentUserId();
        if (userId == null) throw new IllegalStateException("No authenticated user");
        return userId;
    }

    private static Status status(String raw) {
        try {
            return Status.valueOf(raw);
        } catch (RuntimeException malformed) {
            return Status.FAILED;
        }
    }

    private static Priority priority(String raw) {
        if (raw == null || raw.isBlank()) return Priority.NORMAL;
        try {
            return Priority.valueOf(raw);
        } catch (RuntimeException malformed) {
            return Priority.NORMAL;
        }
    }

    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() <= MAX_OUTPUT_CHARS ? value
                : value.substring(0, MAX_OUTPUT_CHARS) + "…";
    }

    private static long elapsedMillis(Instant start, Instant end) {
        return Math.max(0, Duration.between(start, end).toMillis());
    }

    public record Capacity(int running, int queued, int runningLimit, int queueLimit,
                           int available, int ownedRunning, int ownedQueued, int ownerQueueLimit,
                           int ownedQueueAvailable, boolean ownerSaturated,
                           int batchQueueLimit, int nonInteractiveQueueLimit,
                           int ownerBatchQueueLimit, int ownerNonInteractiveQueueLimit,
                           int queuedInteractive, int queuedNormal, int queuedBatch,
                           int ownedQueuedInteractive, int ownedQueuedNormal, int ownedQueuedBatch,
                           int activeOwners,
                           long oldestQueueWaitMs,
                           long oldestInteractiveQueueWaitMs, long oldestNormalQueueWaitMs,
                           long oldestBatchQueueWaitMs,
                           boolean saturated, String schedulingPolicy) {}

    /** The task body; may poll {@link Task#cancelRequested()} for cooperative cancellation. */
    @FunctionalInterface
    public interface TaskBody {
        String run(Task task) throws Exception;
    }
}
