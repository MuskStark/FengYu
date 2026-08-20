package fan.summer.fengyu.ai.tasks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.ai.tools.AiPermissionContext;
import fan.summer.fengyu.ai.tools.AiPermissionMode;
import fan.summer.fengyu.ai.workflow.WorkflowExecutionService;
import fan.summer.fengyu.ai.workflow.WorkflowService;
import fan.summer.fengyu.ai.workflow.WorkflowWebhookTriggerService;
import fan.summer.fengyu.database.entity.ai.WorkflowScheduleEntity;
import fan.summer.fengyu.database.repository.ai.WorkflowScheduleRepository;
import fan.summer.fengyu.security.SecurityContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Durable recurring workflow scheduling. Definitions and delivery state live in the database;
 * the in-memory map is only the active execution index rebuilt at application startup.
 *
 * <p>Delivery is deliberately at-most-once around process crashes. A due occurrence is claimed
 * durably and its next fixed-rate boundary is advanced <em>before</em> a background task is
 * submitted. If the process dies inside that narrow window, startup records the uncertain claim
 * and does not replay it, avoiding duplicate writes/messages/charges. Multiple occurrences missed
 * while the application was stopped are coalesced into one immediate fire, with the excess count
 * retained as {@code missedFires}.
 *
 * <p>Each successful firing submits a normal {@link BackgroundTaskRegistry} task running the
 * published workflow, so output/wait/kill apply exactly as they do to manually submitted work.
 */
@Service
public class BackgroundTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(BackgroundTaskScheduler.class);
    private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final TypeReference<Map<String, Object>> INPUT_MAP = new TypeReference<>() {};
    private static final String ACTIVE = "ACTIVE";
    private static final String COMPLETED = "COMPLETED";
    private static final String EXPIRED = "EXPIRED";
    private static final String CANCELLED = "CANCELLED";
    private static final String INTERRUPTED_CLAIM =
            "Previous scheduled occurrence was interrupted by application restart; "
                    + "it was not replayed to avoid duplicate side effects.";
    private static final String WEAKENED_ISOLATION =
            "Schedule paused: it was created with the plugin sandbox enabled; "
                    + "re-enable the sandbox before it can run.";

    static final int MIN_INTERVAL_SECONDS = 60;
    static final int MAX_ACTIVE_SCHEDULES = 50;
    static final int EXPIRY_DAYS = 7;

    /** One schedule definition plus the active runtime state mirrored to persistence. */
    public static final class Schedule {
        final String id;
        final long userId;
        final String workflowId;
        final Map<String, Object> inputs;
        final int intervalSeconds;
        final boolean recurring;
        final AiPermissionMode permissionMode;
        final String sandboxProfile;
        final Instant createdAt;
        final Instant expiresAt;
        volatile String status;
        volatile Instant nextFireAt;
        volatile Instant claimedAt;
        volatile Instant lastFireAt;
        volatile String lastTaskId;
        volatile String lastError;
        volatile int fires;
        volatile int missedFires;

        String id() { return id; }
        String lastTaskId() { return lastTaskId; }

        Schedule(long userId, String workflowId, Map<String, Object> inputs,
                 int intervalSeconds, boolean recurring, boolean fireImmediately,
                 AiPermissionMode permissionMode, String sandboxProfile, Instant now) {
            this.id = UUID.randomUUID().toString();
            this.userId = userId;
            this.workflowId = workflowId;
            this.inputs = immutableInputs(inputs);
            this.intervalSeconds = intervalSeconds;
            this.recurring = recurring;
            this.permissionMode = permissionMode == null
                    ? AiPermissionMode.ASK_FOR_APPROVAL : permissionMode;
            this.sandboxProfile = normalizeSandboxProfile(sandboxProfile);
            this.createdAt = now;
            this.expiresAt = now.plusSeconds(EXPIRY_DAYS * 24L * 3600);
            this.status = ACTIVE;
            this.nextFireAt = fireImmediately ? now : now.plusSeconds(intervalSeconds);
        }

        Schedule(WorkflowScheduleEntity entity, Map<String, Object> inputs) {
            if (entity.getIntervalSeconds() < MIN_INTERVAL_SECONDS
                    || entity.getCreatedAt() == null || entity.getExpiresAt() == null
                    || entity.getNextFireAt() == null || entity.getWorkflowId() == null
                    || entity.getWorkflowId().isBlank()) {
                throw new IllegalArgumentException("Schedule definition is incomplete or invalid");
            }
            this.id = entity.getId();
            this.userId = entity.getUserId();
            this.workflowId = entity.getWorkflowId();
            this.inputs = immutableInputs(inputs);
            this.intervalSeconds = entity.getIntervalSeconds();
            this.recurring = entity.isRecurring();
            this.permissionMode = permissionMode(entity.getPermissionMode());
            this.sandboxProfile = normalizeSandboxProfile(entity.getSandboxProfile());
            this.createdAt = entity.getCreatedAt();
            this.expiresAt = entity.getExpiresAt();
            this.status = entity.getStatus();
            this.nextFireAt = entity.getNextFireAt();
            this.claimedAt = entity.getClaimedAt();
            this.lastFireAt = entity.getLastFireAt();
            this.lastTaskId = entity.getLastTaskId();
            this.lastError = entity.getLastError();
            this.fires = entity.getFires();
            this.missedFires = entity.getMissedFires();
        }

        private static AiPermissionMode permissionMode(String raw) {
            try {
                return AiPermissionMode.valueOf(raw);
            } catch (RuntimeException unknown) {
                return AiPermissionMode.ASK_FOR_APPROVAL;
            }
        }

        /** Unknown persisted values fail closed instead of silently weakening isolation. */
        private static String normalizeSandboxProfile(String raw) {
            return "unsandboxed".equals(raw) ? "unsandboxed" : "sandboxed";
        }

        private static Map<String, Object> immutableInputs(Map<String, Object> inputs) {
            if (inputs == null || inputs.isEmpty()) return Map.of();
            // JSON objects legitimately contain null values; Map.copyOf rejects them.
            return Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        }
    }

    private final Map<String, Schedule> schedules = new ConcurrentHashMap<>();
    private final BackgroundTaskRegistry tasks;
    /** Lazy: the execution service transitively depends on the tool registry. */
    private final ObjectProvider<WorkflowExecutionService> executions;
    private final ObjectProvider<WorkflowService> workflows;
    private final ObjectProvider<WorkflowWebhookTriggerService> webhookTriggers;
    private final WorkflowScheduleRepository repository;
    private final SecurityContext securityContext;
    private final boolean tickerEnabled;
    private final BooleanSupplier unsandboxedPlugins;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread ticker;

    @Autowired
    public BackgroundTaskScheduler(BackgroundTaskRegistry tasks,
            ObjectProvider<WorkflowExecutionService> executions,
            ObjectProvider<WorkflowService> workflows,
            ObjectProvider<WorkflowWebhookTriggerService> webhookTriggers,
            WorkflowScheduleRepository repository,
            SecurityContext securityContext) {
        this(tasks, executions, workflows, webhookTriggers, repository, securityContext, true,
                fan.summer.fengyu.ai.service.AiConfigServiceHeadless::isUnsandboxedPluginsEnabled);
    }

    /** Package-private deterministic constructor: tests drive {@link #tick()} themselves. */
    BackgroundTaskScheduler(BackgroundTaskRegistry tasks,
            ObjectProvider<WorkflowExecutionService> executions,
            ObjectProvider<WorkflowService> workflows,
            WorkflowScheduleRepository repository,
            SecurityContext securityContext,
            boolean tickerEnabled) {
        this(tasks, executions, workflows, null, repository, securityContext, tickerEnabled,
                () -> false);
    }

    BackgroundTaskScheduler(BackgroundTaskRegistry tasks,
            ObjectProvider<WorkflowExecutionService> executions,
            ObjectProvider<WorkflowService> workflows,
            WorkflowScheduleRepository repository,
            SecurityContext securityContext,
            boolean tickerEnabled,
            BooleanSupplier unsandboxedPlugins) {
        this(tasks, executions, workflows, null, repository, securityContext, tickerEnabled,
                unsandboxedPlugins);
    }

    private BackgroundTaskScheduler(BackgroundTaskRegistry tasks,
            ObjectProvider<WorkflowExecutionService> executions,
            ObjectProvider<WorkflowService> workflows,
            ObjectProvider<WorkflowWebhookTriggerService> webhookTriggers,
            WorkflowScheduleRepository repository,
            SecurityContext securityContext,
            boolean tickerEnabled,
            BooleanSupplier unsandboxedPlugins) {
        this.tasks = tasks;
        this.executions = executions;
        this.workflows = workflows;
        this.webhookTriggers = webhookTriggers;
        this.repository = repository;
        this.securityContext = securityContext;
        this.tickerEnabled = tickerEnabled;
        this.unsandboxedPlugins = unsandboxedPlugins;
    }

    /** Rebuilds the active execution index and resolves any crash-interrupted claims. */
    @PostConstruct
    synchronized void recoverSchedules() {
        for (WorkflowScheduleEntity entity : repository.findByClaimedAtIsNotNull()) {
            entity.setClaimedAt(null);
            entity.setLastError(INTERRUPTED_CLAIM);
            repository.save(entity);
            log.warn("schedule {} had an interrupted delivery claim; occurrence not replayed",
                    entity.getId());
        }

        Instant now = Instant.now();
        for (WorkflowScheduleEntity entity : repository.findByStatusOrderByCreatedAtAsc(ACTIVE)) {
            try {
                if (entity.getExpiresAt() == null) {
                    throw new IllegalArgumentException("Schedule expiry is missing");
                }
                if (!entity.getExpiresAt().isAfter(now)) {
                    entity.setStatus(EXPIRED);
                    repository.save(entity);
                    continue;
                }
                Schedule schedule = new Schedule(entity, parseInputs(entity.getInputsJson()));
                schedules.put(schedule.id, schedule);
            } catch (RuntimeException malformed) {
                entity.setStatus(CANCELLED);
                entity.setLastError("Schedule could not be restored: " + errorMessage(malformed));
                repository.save(entity);
                log.warn("schedule {} disabled during recovery: {}",
                        entity.getId(), errorMessage(malformed));
            }
        }
        if (!schedules.isEmpty()) {
            startTicker();
            log.info("restored {} active workflow schedule(s)", schedules.size());
        }
    }

    /** Creates a durable schedule after validating its interval, owner and published workflow. */
    public synchronized Schedule create(String workflowId, Map<String, Object> inputs,
                                        int intervalSeconds, boolean recurring,
                                        boolean fireImmediately) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId is required");
        }
        if (intervalSeconds < MIN_INTERVAL_SECONDS) {
            throw new IllegalArgumentException("Schedule interval must be at least "
                    + MIN_INTERVAL_SECONDS + " seconds");
        }
        long userId = currentUserId();
        if (activeCount(userId) >= MAX_ACTIVE_SCHEDULES) {
            throw new IllegalStateException("Too many active schedules ("
                    + MAX_ACTIVE_SCHEDULES + "); delete some first");
        }
        WorkflowService workflowService = workflows.getIfAvailable();
        if (workflowService != null) {
            // Compile once now so unpublished definitions, invalid inputs and missing snapshots
            // fail at creation instead of becoming a permanently failing future trigger.
            workflowService.compile(workflowId, inputs == null ? Map.of() : inputs, true);
        }
        String inputsJson = toJson(inputs == null ? Map.of() : inputs);
        Instant now = Instant.now();
        Schedule schedule = new Schedule(userId, workflowId,
                parseInputs(inputsJson), intervalSeconds, recurring, fireImmediately,
                AiPermissionContext.current(), currentSandboxProfile(), now);
        repository.save(toEntity(schedule));
        schedules.put(schedule.id, schedule);
        startTicker();
        log.info("schedule {} created: workflow {} every {}s (recurring={})",
                schedule.id, workflowId, intervalSeconds, recurring);
        return schedule;
    }

    /** Active schedules belonging to the current user, oldest first. */
    public List<Map<String, Object>> list() {
        long userId = currentUserId();
        List<Schedule> ordered = schedules.values().stream()
                .filter(schedule -> schedule.userId == userId)
                .sorted((a, b) -> a.createdAt.compareTo(b.createdAt))
                .toList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Schedule schedule : ordered) out.add(summary(schedule));
        return out;
    }

    /** True when an active schedule owned by the current user was durably cancelled. */
    public synchronized boolean delete(String scheduleId) {
        long userId = currentUserId();
        Schedule schedule = schedules.get(scheduleId);
        if (schedule == null || schedule.userId != userId) return false;
        schedule.status = CANCELLED;
        schedule.claimedAt = null;
        repository.save(toEntity(schedule));
        schedules.remove(scheduleId, schedule);
        return true;
    }

    /** Atomically cancels a workflow's schedules and deletes the workflow definition. */
    @Transactional
    public synchronized WorkflowDeleteResult deleteWorkflow(String workflowId) {
        long userId = currentUserId();
        WorkflowService workflowService = workflows.getIfAvailable();
        if (workflowService == null) {
            throw new IllegalStateException("Workflow service is unavailable");
        }
        List<String> cancelledIds = new ArrayList<>();
        int deleted = 0;
        for (WorkflowScheduleEntity entity : repository
                .findByWorkflowIdAndUserIdAndStatus(workflowId, userId, ACTIVE)) {
            entity.setStatus(CANCELLED);
            entity.setClaimedAt(null);
            repository.save(entity);
            cancelledIds.add(entity.getId());
            deleted++;
        }
        WorkflowWebhookTriggerService webhookService = webhookTriggers == null
                ? null : webhookTriggers.getIfAvailable();
        int cancelledWebhookTriggers = webhookService == null
                ? 0 : webhookService.cancelForWorkflow(workflowId, userId);
        workflowService.delete(workflowId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    cancelledIds.forEach(schedules::remove);
                }
            });
        } else {
            cancelledIds.forEach(schedules::remove);
        }
        return new WorkflowDeleteResult(deleted, cancelledWebhookTriggers);
    }

    public record WorkflowDeleteResult(int cancelledSchedules,
                                       int cancelledWebhookTriggers) {}

    public Map<String, Object> summary(Schedule schedule) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scheduleId", schedule.id);
        out.put("workflowId", schedule.workflowId);
        out.put("intervalSeconds", schedule.intervalSeconds);
        out.put("recurring", schedule.recurring);
        out.put("nextFireAt", schedule.nextFireAt.toString());
        out.put("fires", schedule.fires);
        out.put("missedFires", schedule.missedFires);
        out.put("lastFireAt", schedule.lastFireAt == null ? null : schedule.lastFireAt.toString());
        out.put("lastTaskId", schedule.lastTaskId);
        out.put("lastError", schedule.lastError);
        out.put("createdAt", schedule.createdAt.toString());
        out.put("expiresAt", schedule.expiresAt.toString());
        out.put("persistent", true);
        out.put("sandboxProfile", schedule.sandboxProfile);
        return out;
    }

    int activeCount() {
        return activeCount(currentUserId());
    }

    private int activeCount(long userId) {
        return (int) schedules.values().stream()
                .filter(schedule -> schedule.userId == userId && ACTIVE.equals(schedule.status))
                .count();
    }

    private void startTicker() {
        if (!tickerEnabled) return;
        if (!running.compareAndSet(false, true)) return;
        ticker = Thread.ofVirtual().name("task-scheduler").start(() -> {
            while (running.get()) {
                try {
                    tick();
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    log.warn("scheduler tick failed: {}", e.getMessage());
                }
            }
        });
    }

    @PreDestroy
    void stopTicker() {
        running.set(false);
        Thread worker = ticker;
        if (worker != null) worker.interrupt();
    }

    /** Fires due schedules; package-private for tests to drive deterministically. */
    synchronized void tick() {
        Instant now = Instant.now();
        for (Schedule schedule : List.copyOf(schedules.values())) {
            if (!ACTIVE.equals(schedule.status)) continue;
            if (!schedule.expiresAt.isAfter(now)) {
                schedule.status = EXPIRED;
                schedule.claimedAt = null;
                repository.save(toEntity(schedule));
                schedules.remove(schedule.id, schedule);
                log.info("schedule {} expired after {} days", schedule.id, EXPIRY_DAYS);
                continue;
            }
            if (schedule.nextFireAt.isAfter(now)) continue;
            if (isIsolationWeakened(schedule)) {
                if (!WEAKENED_ISOLATION.equals(schedule.lastError)) {
                    acknowledgeFailure(schedule, WEAKENED_ISOLATION);
                }
                continue;
            }
            if (!claim(schedule, now)) continue;
            if (!schedule.recurring) schedules.remove(schedule.id, schedule);
            fire(schedule, now);
        }
    }

    /** Durably advances the fixed-rate clock before submission (at-most-once crash boundary). */
    private boolean claim(Schedule schedule, Instant now) {
        Instant previousNext = schedule.nextFireAt;
        String previousStatus = schedule.status;
        int previousMissed = schedule.missedFires;
        Instant previousClaim = schedule.claimedAt;
        long dueOccurrences = 1;
        if (schedule.recurring) {
            long overdueSeconds = Math.max(0, Duration.between(previousNext, now).getSeconds());
            dueOccurrences += overdueSeconds / schedule.intervalSeconds;
            schedule.nextFireAt = previousNext.plusSeconds(
                    Math.multiplyExact(dueOccurrences, (long) schedule.intervalSeconds));
            long missed = Math.min(Integer.MAX_VALUE,
                    (long) schedule.missedFires + dueOccurrences - 1);
            schedule.missedFires = (int) missed;
        } else {
            schedule.status = COMPLETED;
        }
        schedule.claimedAt = now;
        try {
            repository.save(toEntity(schedule));
            return true;
        } catch (RuntimeException persistenceFailure) {
            schedule.nextFireAt = previousNext;
            schedule.status = previousStatus;
            schedule.missedFires = previousMissed;
            schedule.claimedAt = previousClaim;
            log.warn("schedule {} occurrence not claimed: {}",
                    schedule.id, errorMessage(persistenceFailure));
            return false;
        }
    }

    private void fire(Schedule schedule, Instant now) {
        WorkflowExecutionService execution = executions.getIfAvailable();
        if (execution == null) {
            acknowledgeFailure(schedule, "Workflow execution unavailable");
            return;
        }
        try {
            BackgroundTaskRegistry.Task task = tasks.submit(schedule.userId,
                    BackgroundTaskRegistry.Priority.BATCH, "workflow-schedule",
                    "scheduled workflow " + schedule.workflowId, runningTask -> {
                        AiPermissionContext.set(schedule.permissionMode);
                        fan.summer.fengyu.ai.agent.AgentRun run;
                        try {
                            run = execution.startForAi(schedule.workflowId, schedule.inputs);
                        } finally {
                            AiPermissionContext.clear();
                        }
                        runningTask.onCancel(() -> {
                            run.markCancelled();
                            run.approve(null);
                        });
                        return execution.waitForAiRun(run);
                    });
            schedule.fires++;
            schedule.lastFireAt = now;
            schedule.lastTaskId = task.id();
            schedule.lastError = null;
            schedule.claimedAt = null;
            repository.save(toEntity(schedule));
        } catch (Exception error) {
            acknowledgeFailure(schedule, errorMessage(error));
            log.warn("schedule {} fire failed: {}", schedule.id, schedule.lastError);
        }
    }

    private void acknowledgeFailure(Schedule schedule, String message) {
        schedule.lastError = message;
        schedule.claimedAt = null;
        try {
            repository.save(toEntity(schedule));
        } catch (RuntimeException persistenceFailure) {
            log.warn("schedule {} failure acknowledgement not persisted: {}",
                    schedule.id, errorMessage(persistenceFailure));
        }
    }

    /** Executes one schedule body for unit tests without the workflow execution service. */
    void fireForTest(Schedule schedule, BackgroundTaskRegistry.TaskBody body) {
        BackgroundTaskRegistry.Task task = tasks.submit(schedule.userId,
                BackgroundTaskRegistry.Priority.BATCH, "workflow-schedule",
                "scheduled workflow " + schedule.workflowId, body);
        schedule.fires++;
        schedule.lastFireAt = Instant.now();
        schedule.lastTaskId = task.id();
        schedule.lastError = null;
        schedule.claimedAt = null;
        repository.save(toEntity(schedule));
    }

    private WorkflowScheduleEntity toEntity(Schedule schedule) {
        WorkflowScheduleEntity entity = new WorkflowScheduleEntity();
        entity.setId(schedule.id);
        entity.setUserId(schedule.userId);
        entity.setWorkflowId(schedule.workflowId);
        entity.setInputsJson(toJson(schedule.inputs));
        entity.setIntervalSeconds(schedule.intervalSeconds);
        entity.setRecurring(schedule.recurring);
        entity.setPermissionMode(schedule.permissionMode.name());
        entity.setSandboxProfile(schedule.sandboxProfile);
        entity.setStatus(schedule.status);
        entity.setCreatedAt(schedule.createdAt);
        entity.setExpiresAt(schedule.expiresAt);
        entity.setNextFireAt(schedule.nextFireAt);
        entity.setClaimedAt(schedule.claimedAt);
        entity.setLastFireAt(schedule.lastFireAt);
        entity.setLastTaskId(schedule.lastTaskId);
        entity.setLastError(schedule.lastError);
        entity.setFires(schedule.fires);
        entity.setMissedFires(schedule.missedFires);
        return entity;
    }

    private long currentUserId() {
        Long id = securityContext.currentUserId();
        if (id == null) throw new IllegalStateException("No authenticated user");
        return id;
    }

    private String currentSandboxProfile() {
        return unsandboxedPlugins.getAsBoolean() ? "unsandboxed" : "sandboxed";
    }

    private boolean isIsolationWeakened(Schedule schedule) {
        return "sandboxed".equals(schedule.sandboxProfile)
                && "unsandboxed".equals(currentSandboxProfile());
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception malformed) {
            throw new IllegalArgumentException("Schedule inputs cannot be serialized", malformed);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseInputs(String value) {
        try {
            return JSON.readValue(value == null || value.isBlank() ? "{}" : value, INPUT_MAP);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception malformed) {
            throw new IllegalArgumentException("Schedule inputs are malformed", malformed);
        }
    }

    private static String errorMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
