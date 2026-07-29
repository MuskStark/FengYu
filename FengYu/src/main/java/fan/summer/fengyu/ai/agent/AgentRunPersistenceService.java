package fan.summer.fengyu.ai.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.database.SecurityConstants;
import fan.summer.fengyu.database.entity.ai.AgentRunEntity;
import fan.summer.fengyu.database.entity.ai.AgentRunEventEntity;
import fan.summer.fengyu.database.repository.ai.AgentRunEventRepository;
import fan.summer.fengyu.database.repository.ai.AgentRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Persists agent snapshots and lifecycle events without coupling the runner to JPA. */
@Service
public class AgentRunPersistenceService {
    private static final Logger log = LoggerFactory.getLogger(AgentRunPersistenceService.class);
    private static final long USER_ID = SecurityConstants.LOCAL_VIRTUAL_USER_ID;
    private static final EnumSet<AgentRunStatus> NON_TERMINAL = EnumSet.of(
            AgentRunStatus.PLANNING,
            AgentRunStatus.AWAITING_PLAN_APPROVAL,
            AgentRunStatus.EXECUTING,
            AgentRunStatus.AWAITING_STEP_APPROVAL);

    private final AgentRunRepository runs;
    private final AgentRunEventRepository events;
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    public AgentRunPersistenceService(AgentRunRepository runs, AgentRunEventRepository events) {
        this.runs = runs;
        this.events = events;
    }

    @Transactional
    public void create(AgentRun run, String resumedFrom) {
        LocalDateTime now = LocalDateTime.now();
        AgentRunEntity entity = new AgentRunEntity();
        entity.setId(run.getRunId());
        entity.setUserId(USER_ID);
        entity.setGoal(run.getGoal());
        entity.setStatus(run.getStatus().name());
        entity.setConfigJson(write(run.getConfig()));
        entity.setPlanJson(run.getPlan() == null ? null : write(run.getPlan()));
        entity.setExecutionsJson(write(run.getExecutions()));
        entity.setResumedFrom(resumedFrom);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        runs.save(entity);
        appendEvent(run.getRunId(), "created", Map.of(
                "goal", run.getGoal(),
                "resumedFrom", resumedFrom == null ? "" : resumedFrom));
    }

    /**
     * Wraps the live transport sink. Transport delivery happens first; persistence failures are
     * logged and never terminate an otherwise healthy agent run.
     */
    public AgentEventSink persisting(AgentRun run, AgentEventSink delegate) {
        return new AgentEventSink() {
            @Override public void onPlanToken(String delta) {
                delegate.onPlanToken(delta);
            }

            @Override public void onPlanReady(AgentPlan plan) {
                delegate.onPlanReady(plan);
                persist(run, "plan_ready", plan, null, null);
            }

            @Override public void onPlanApprovalRequested() {
                delegate.onPlanApprovalRequested();
                persist(run, "plan_approval_requested", Map.of(), null, null);
            }

            @Override public void onStepStart(int index) {
                delegate.onStepStart(index);
                persist(run, "step_start", Map.of("index", index), null, null);
            }

            @Override public void onStepComplete(int index, String result) {
                delegate.onStepComplete(index, result);
                persist(run, "step_complete",
                        Map.of("index", index, "result", result == null ? "" : result), null, null);
            }

            @Override public void onStepApprovalRequested(int index) {
                delegate.onStepApprovalRequested(index);
                persist(run, "step_approval_requested", Map.of("index", index), null, null);
            }

            @Override public void onComplete(String summary) {
                delegate.onComplete(summary);
                persist(run, "complete", Map.of("summary", summary == null ? "" : summary),
                        summary, null);
            }

            @Override public void onError(String message) {
                delegate.onError(message);
                String type = run.getStatus() == AgentRunStatus.CANCELLED ? "cancelled" : "error";
                persist(run, type, Map.of("message", message == null ? "" : message),
                        null, message);
            }
        };
    }

    private void persist(AgentRun run, String type, Object data, String summary, String error) {
        try {
            updateSnapshot(run, summary, error);
            appendEvent(run.getRunId(), type, data);
        } catch (RuntimeException e) {
            log.warn("Could not persist agent {} event {}: {}", run.getRunId(), type, e.getMessage());
        }
    }

    @Transactional
    public void updateSnapshot(AgentRun run, String summary, String error) {
        AgentRunEntity entity = runs.findByIdAndUserId(run.getRunId(), USER_ID).orElse(null);
        if (entity == null) return;
        entity.setStatus(run.getStatus().name());
        entity.setPlanJson(run.getPlan() == null ? null : write(run.getPlan()));
        entity.setExecutionsJson(write(run.getExecutions()));
        if (summary != null) entity.setSummary(summary);
        if (error != null) entity.setErrorMessage(error);
        entity.setUpdatedAt(LocalDateTime.now());
        if (!NON_TERMINAL.contains(run.getStatus())) entity.setCompletedAt(LocalDateTime.now());
        runs.save(entity);
    }

    @Transactional
    public synchronized void appendEvent(String runId, String type, Object data) {
        long seq = sequences.computeIfAbsent(runId, id -> new AtomicLong(
                events.findTopByRunIdOrderBySeqDesc(id).map(AgentRunEventEntity::getSeq).orElse(-1L)))
                .incrementAndGet();
        AgentRunEventEntity event = new AgentRunEventEntity();
        event.setRunId(runId);
        event.setSeq(seq);
        event.setType(type);
        event.setDataJson(write(data));
        event.setCreatedAt(LocalDateTime.now());
        events.save(event);
    }

    public List<RunSummary> list() {
        return runs.findByUserIdOrderByUpdatedAtDesc(USER_ID).stream()
                .map(entity -> new RunSummary(
                        entity.getId(), entity.getGoal(), entity.getStatus(),
                        entity.getSummary(), entity.getErrorMessage(), entity.getResumedFrom(),
                        entity.getCreatedAt(), entity.getUpdatedAt(), entity.getCompletedAt()))
                .toList();
    }

    public RunDetail detail(String id) {
        AgentRunEntity entity = runs.findByIdAndUserId(id, USER_ID)
                .orElseThrow(() -> new IllegalArgumentException("Unknown persisted run: " + id));
        List<PersistedEvent> eventList = events.findByRunIdOrderBySeqAsc(id).stream()
                .map(event -> new PersistedEvent(
                        event.getSeq(), event.getType(), readMap(event.getDataJson()),
                        event.getCreatedAt()))
                .toList();
        return new RunDetail(
                entity.getId(), entity.getGoal(), entity.getStatus(),
                read(entity.getConfigJson(), AgentRunConfig.class),
                readNullable(entity.getPlanJson(), AgentPlan.class),
                readExecutions(entity.getExecutionsJson()),
                entity.getSummary(), entity.getErrorMessage(), entity.getResumedFrom(),
                entity.getCreatedAt(), entity.getUpdatedAt(), entity.getCompletedAt(), eventList);
    }

    public ResumeState resumeState(String id) {
        RunDetail detail = detail(id);
        if (detail.plan() == null) {
            throw new IllegalStateException("Persisted run has no executable plan");
        }
        if (!List.of(AgentRunStatus.FAILED.name(), AgentRunStatus.CANCELLED.name())
                .contains(detail.status())) {
            throw new IllegalStateException("Only failed, cancelled, or interrupted runs can be resumed");
        }
        AgentRunConfig original = detail.config();
        AgentRunConfig reviewed = new AgentRunConfig(
                true,
                original.requireStepApproval(),
                original.replanOnFailure(),
                original.maxReplans());
        List<StepExecution> completed = detail.executions().stream()
                .filter(execution -> execution.status() == StepStatus.COMPLETED)
                .toList();
        return new ResumeState(detail.goal(), reviewed, detail.plan(), completed, detail.id());
    }

    /** Converts process-local in-flight states into explicit recoverable failures on restart. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void markInterruptedRuns() {
        List<String> statuses = NON_TERMINAL.stream().map(Enum::name).toList();
        for (AgentRunEntity entity : runs.findByStatusIn(statuses)) {
            entity.setStatus(AgentRunStatus.FAILED.name());
            entity.setErrorMessage("Run interrupted by application restart; review and resume it.");
            entity.setUpdatedAt(LocalDateTime.now());
            entity.setCompletedAt(LocalDateTime.now());
            runs.save(entity);
            appendEvent(entity.getId(), "interrupted",
                    Map.of("message", entity.getErrorMessage()));
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize agent state", e);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read persisted agent state", e);
        }
    }

    private <T> T readNullable(String value, Class<T> type) {
        return value == null || value.isBlank() ? null : read(value, type);
    }

    private List<StepExecution> readExecutions(String value) {
        try {
            return json.readValue(value, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Could not read persisted agent executions", e);
        }
    }

    private Map<String, Object> readMap(String value) {
        try {
            return json.readValue(value, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Could not read persisted agent event", e);
        }
    }

    public record RunSummary(
            String id, String goal, String status, String summary, String error,
            String resumedFrom, LocalDateTime createdAt, LocalDateTime updatedAt,
            LocalDateTime completedAt) {}

    public record PersistedEvent(long seq, String type, Map<String, Object> data,
                                 LocalDateTime createdAt) {}

    public record RunDetail(
            String id, String goal, String status, AgentRunConfig config, AgentPlan plan,
            List<StepExecution> executions, String summary, String error, String resumedFrom,
            LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime completedAt,
            List<PersistedEvent> events) {}

    public record ResumeState(
            String goal, AgentRunConfig config, AgentPlan plan,
            List<StepExecution> completedExecutions, String resumedFrom) {}
}
