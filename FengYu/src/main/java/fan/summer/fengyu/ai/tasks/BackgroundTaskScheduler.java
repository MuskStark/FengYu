package fan.summer.fengyu.ai.tasks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import fan.summer.fengyu.ai.tools.AiPermissionContext;
import fan.summer.fengyu.ai.tools.AiPermissionMode;
import fan.summer.fengyu.ai.util.JsonHelper;
import fan.summer.fengyu.ai.workflow.WorkflowExecutionService;
import fan.summer.fengyu.ai.workflow.WorkflowService;

/**
 * Recurring workflow scheduling — the durable counterpart of the one-shot background
 * tasks, following the semantics terminal agents converged on: a minimum interval of
 * 60 seconds, at most 50 active schedules, automatic expiry after 7 days, optional
 * immediate first fire, and non-recurring one-shots for delayed execution. Schedules
 * are in-memory by default (a restart clears them) — deliberate, matching the
 * non-durable default of the model this follows.
 *
 * <p>Each firing submits a normal {@link BackgroundTaskRegistry} task running the
 * published workflow, so output/wait/kill apply to scheduled runs exactly like
 * manual ones.
 */
@Service
public class BackgroundTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(BackgroundTaskScheduler.class);
    static final int MIN_INTERVAL_SECONDS = 60;
    static final int MAX_ACTIVE_SCHEDULES = 50;
    static final int EXPIRY_DAYS = 7;

    /** One schedule definition plus its runtime state. */
    public static final class Schedule {
        final String id = UUID.randomUUID().toString();
        final String workflowId;
        final Map<String, Object> inputs;
        final int intervalSeconds;
        final boolean recurring;
        /** The submitting chat's permission mode, restored around each fire so scheduled
         *  workflow steps evaluate rules/approvals under the mode that created the schedule. */
        final AiPermissionMode permissionMode;
        final Instant createdAt = Instant.now();
        final Instant expiresAt = createdAt.plusSeconds(EXPIRY_DAYS * 24L * 3600);
        volatile Instant nextFireAt;
        volatile String lastTaskId;
        volatile String lastError;
        volatile int fires;

        String id() { return id; }
        String lastTaskId() { return lastTaskId; }

        Schedule(String workflowId, Map<String, Object> inputs, int intervalSeconds,
                 boolean recurring, boolean fireImmediately, AiPermissionMode permissionMode) {
            this.workflowId = workflowId;
            this.inputs = inputs == null ? Map.of() : inputs;
            this.intervalSeconds = intervalSeconds;
            this.recurring = recurring;
            this.permissionMode = permissionMode == null
                    ? AiPermissionMode.ASK_FOR_APPROVAL : permissionMode;
            this.nextFireAt = fireImmediately ? Instant.now()
                    : Instant.now().plusSeconds(intervalSeconds);
        }
    }

    private final Map<String, Schedule> schedules = new ConcurrentHashMap<>();
    private final BackgroundTaskRegistry tasks;
    /** Lazy: the execution service transitively depends on the tool registry. */
    private final ObjectProvider<WorkflowExecutionService> executions;
    private final ObjectProvider<WorkflowService> workflows;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread ticker;

    public BackgroundTaskScheduler(BackgroundTaskRegistry tasks,
            ObjectProvider<WorkflowExecutionService> executions,
            ObjectProvider<WorkflowService> workflows) {
        this.tasks = tasks;
        this.executions = executions;
        this.workflows = workflows;
    }

    /** Creates a schedule; validates the interval, the active cap, and the workflow. */
    public synchronized Schedule create(String workflowId, Map<String, Object> inputs,
                                        int intervalSeconds, boolean recurring,
                                        boolean fireImmediately) {
        if (intervalSeconds < MIN_INTERVAL_SECONDS) {
            throw new IllegalArgumentException("Schedule interval must be at least "
                    + MIN_INTERVAL_SECONDS + " seconds");
        }
        if (activeCount() >= MAX_ACTIVE_SCHEDULES) {
            throw new IllegalStateException("Too many active schedules ("
                    + MAX_ACTIVE_SCHEDULES + "); delete some first");
        }
        WorkflowService workflowService = workflows.getIfAvailable();
        if (workflowService != null) {
            workflowService.get(workflowId); // throws for an unknown workflow
        }
        Schedule schedule = new Schedule(workflowId, inputs, intervalSeconds,
                recurring, fireImmediately, AiPermissionContext.current());
        schedules.put(schedule.id, schedule);
        startTicker();
        log.info("schedule {} created: workflow {} every {}s (recurring={})",
                schedule.id, workflowId, intervalSeconds, recurring);
        return schedule;
    }

    public List<Map<String, Object>> list() {
        List<Schedule> ordered = new ArrayList<>(schedules.values());
        ordered.sort((a, b) -> a.createdAt.compareTo(b.createdAt));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Schedule schedule : ordered) out.add(summary(schedule));
        return out;
    }

    /** True when the schedule existed and was removed; pending fires stop. */
    public boolean delete(String scheduleId) {
        return schedules.remove(scheduleId) != null;
    }

    public Map<String, Object> summary(Schedule schedule) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("scheduleId", schedule.id);
        out.put("workflowId", schedule.workflowId);
        out.put("intervalSeconds", schedule.intervalSeconds);
        out.put("recurring", schedule.recurring);
        out.put("nextFireAt", schedule.nextFireAt.toString());
        out.put("fires", schedule.fires);
        out.put("lastTaskId", schedule.lastTaskId);
        out.put("lastError", schedule.lastError);
        out.put("expiresAt", schedule.expiresAt.toString());
        return out;
    }

    int activeCount() {
        return schedules.size();
    }

    private void startTicker() {
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

    /**
     * Stops the ticker on context shutdown. The SETUP→APP transition is a full JVM exit, so
     * this matters mostly for tests and any future in-process context restart: without it the
     * once-started virtual thread ticks forever after its schedules are gone.
     */
    @jakarta.annotation.PreDestroy
    void stopTicker() {
        running.set(false);
        Thread worker = ticker;
        if (worker != null) worker.interrupt();
    }

    /** Fires due schedules; package-private for tests to drive deterministically. */
    void tick() {
        Instant now = Instant.now();
        for (Schedule schedule : schedules.values()) {
            if (schedule.expiresAt.isBefore(now)) {
                schedules.remove(schedule.id);
                log.info("schedule {} expired after {} days", schedule.id, EXPIRY_DAYS);
                continue;
            }
            if (schedule.nextFireAt.isAfter(now)) continue;
            fire(schedule);
            if (schedule.recurring) {
                schedule.nextFireAt = now.plusSeconds(schedule.intervalSeconds);
            } else {
                schedules.remove(schedule.id);
            }
        }
    }

    private void fire(Schedule schedule) {
        WorkflowExecutionService execution = executions.getIfAvailable();
        if (execution == null) {
            schedule.lastError = "Workflow execution unavailable";
            return;
        }
        try {
            BackgroundTaskRegistry.Task task = tasks.submit("workflow-schedule",
                    "scheduled workflow " + schedule.workflowId, running -> {
                        // startForAi reads the permission context of the calling thread;
                        // the registry worker thread has none, so restore the submitting
                        // chat's captured mode for the run-creation window.
                        AiPermissionContext.set(schedule.permissionMode);
                        fan.summer.fengyu.ai.agent.AgentRun run;
                        try {
                            run = execution.startForAi(schedule.workflowId, schedule.inputs);
                        } finally {
                            AiPermissionContext.clear();
                        }
                        running.canceller = () -> {
                            run.markCancelled();
                            run.approve(null);
                        };
                        return execution.waitForAiRun(run);
                    });
            schedule.fires++;
            schedule.lastTaskId = task.id();
            schedule.lastError = null;
        } catch (Exception error) {
            schedule.lastError = error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage();
            log.warn("schedule {} fire failed: {}", schedule.id, schedule.lastError);
        }
    }

    /** Executes one due schedule body for tests without the workflow service. */
    void fireForTest(Schedule schedule, BackgroundTaskRegistry.TaskBody body) {
        BackgroundTaskRegistry.Task task = tasks.submit("workflow-schedule",
                "scheduled workflow " + schedule.workflowId, body);
        schedule.fires++;
        schedule.lastTaskId = task.id();
    }

    @SuppressWarnings("unused")
    private static String toJson(Object value) {
        try {
            return JsonHelper.toJson(value);
        } catch (Exception malformed) {
            return String.valueOf(value);
        }
    }
}
