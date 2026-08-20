package fan.summer.fengyu.ai.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.ai.agent.AgentRun;
import fan.summer.fengyu.ai.service.AiConfigServiceHeadless;
import fan.summer.fengyu.ai.tasks.BackgroundTaskCapacityException;
import fan.summer.fengyu.ai.tasks.BackgroundTaskRegistry;
import fan.summer.fengyu.ai.tools.AiPermissionContext;
import fan.summer.fengyu.ai.tools.AiPermissionMode;
import fan.summer.fengyu.database.entity.ai.WorkflowWebhookDeliveryEntity;
import fan.summer.fengyu.database.entity.ai.WorkflowWebhookTriggerEntity;
import fan.summer.fengyu.database.repository.ai.WorkflowWebhookDeliveryRepository;
import fan.summer.fengyu.database.repository.ai.WorkflowWebhookTriggerRepository;
import fan.summer.fengyu.security.SecurityContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Durable loopback-webhook bindings for published workflows.
 *
 * <p>The main application token protects management endpoints. Delivery endpoints instead use a
 * per-trigger secret returned only at creation/rotation and retained solely as a SHA-256 digest.
 * Optional event IDs are also hashed and claimed before task submission, giving concurrent
 * retries at-most-once admission without storing source-system identifiers.
 */
@Service
public class WorkflowWebhookTriggerService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowWebhookTriggerService.class);
    private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final TypeReference<Map<String, Object>> INPUT_MAP = new TypeReference<>() {};
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ACTIVE = "ACTIVE";
    private static final int MAX_EVENT_ID_CHARS = 200;
    private static final int MAX_DELIVERIES_RETAINED = 1_000;
    private static final String WEAKENED_ISOLATION =
            "Webhook paused: it was created with the plugin sandbox enabled; "
                    + "re-enable the sandbox before it can run.";

    private final WorkflowWebhookTriggerRepository triggers;
    private final WorkflowWebhookDeliveryRepository deliveries;
    private final WorkflowService workflows;
    private final WorkflowExecutionService executions;
    private final BackgroundTaskRegistry tasks;
    private final SecurityContext securityContext;
    private final BooleanSupplier unsandboxedPlugins;

    @Autowired
    public WorkflowWebhookTriggerService(WorkflowWebhookTriggerRepository triggers,
            WorkflowWebhookDeliveryRepository deliveries, WorkflowService workflows,
            WorkflowExecutionService executions, BackgroundTaskRegistry tasks,
            SecurityContext securityContext) {
        this(triggers, deliveries, workflows, executions, tasks, securityContext,
                AiConfigServiceHeadless::isUnsandboxedPluginsEnabled);
    }

    WorkflowWebhookTriggerService(WorkflowWebhookTriggerRepository triggers,
            WorkflowWebhookDeliveryRepository deliveries, WorkflowService workflows,
            WorkflowExecutionService executions, BackgroundTaskRegistry tasks,
            SecurityContext securityContext, BooleanSupplier unsandboxedPlugins) {
        this.triggers = triggers;
        this.deliveries = deliveries;
        this.workflows = workflows;
        this.executions = executions;
        this.tasks = tasks;
        this.securityContext = securityContext;
        this.unsandboxedPlugins = unsandboxedPlugins;
    }

    /** Claims left without a task acknowledgement by a crash are never replayed. */
    @PostConstruct
    void recoverInterruptedClaims() {
        List<WorkflowWebhookDeliveryEntity> interrupted = deliveries
                .findByStatusInOrderByAcceptedAtAsc(
                        List.of("CLAIMED", "QUEUED", "SUBMITTED"));
        Instant now = Instant.now();
        for (WorkflowWebhookDeliveryEntity delivery : interrupted) {
            delivery.setStatus("INTERRUPTED");
            delivery.setCompletedAt(now);
            delivery.setError("Webhook delivery was interrupted before task acknowledgement; "
                    + "it was not replayed to avoid duplicate side effects.");
        }
        if (!interrupted.isEmpty()) {
            deliveries.saveAll(interrupted);
            log.warn("restored {} interrupted webhook delivery claim(s)", interrupted.size());
        }
    }

    /** Creates a trigger and returns its plaintext secret exactly once. */
    public CreatedTrigger create(String workflowId, String name,
                                 Map<String, Object> defaultInputs) {
        return create(workflowId, name, defaultInputs, AiPermissionContext.current());
    }

    public CreatedTrigger create(String workflowId, String name,
                                 Map<String, Object> defaultInputs,
                                 AiPermissionMode permissionMode) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId is required");
        }
        String safeName = name == null || name.isBlank() ? "Webhook " + workflowId : name.strip();
        if (safeName.length() > 160) {
            throw new IllegalArgumentException("Webhook trigger name must be at most 160 characters");
        }
        Map<String, Object> defaults = copyInputs(defaultInputs);
        rejectEphemeralFileInputs(workflows.get(workflowId));
        workflows.compile(workflowId, defaults, true);

        String secret = generateSecret();
        WorkflowWebhookTriggerEntity entity = new WorkflowWebhookTriggerEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserId(currentUserId());
        entity.setWorkflowId(workflowId);
        entity.setName(safeName);
        entity.setSecretHash(hash(secret));
        entity.setDefaultInputsJson(toJson(defaults));
        entity.setPermissionMode((permissionMode == null
                ? AiPermissionMode.ASK_FOR_APPROVAL : permissionMode).name());
        entity.setSandboxProfile(currentSandboxProfile());
        entity.setStatus(ACTIVE);
        entity.setCreatedAt(Instant.now());
        triggers.save(entity);
        return new CreatedTrigger(summary(entity), secret);
    }

    /** Active triggers belonging to the current user, oldest first. */
    public List<Map<String, Object>> list() {
        return triggers.findByUserIdAndStatusOrderByCreatedAtAsc(currentUserId(), ACTIVE).stream()
                .map(this::summary)
                .toList();
    }

    /** Recent lifecycle records for one owned trigger; payloads and event IDs are never exposed. */
    public List<Map<String, Object>> listDeliveries(String triggerId, Integer requestedLimit) {
        ownedActive(triggerId);
        int limit = requestedLimit == null ? 20 : requestedLimit;
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Webhook delivery limit must be between 1 and 100");
        }
        return deliveries.findByTriggerIdOrderByAcceptedAtDesc(
                        triggerId, PageRequest.of(0, limit)).stream()
                .map(WorkflowWebhookTriggerService::deliverySummary)
                .toList();
    }

    /** Rotates a trigger secret without changing its stable endpoint or delivery history. */
    public CreatedTrigger rotateSecret(String triggerId) {
        WorkflowWebhookTriggerEntity entity = ownedActive(triggerId);
        String secret = generateSecret();
        entity.setSecretHash(hash(secret));
        triggers.save(entity);
        return new CreatedTrigger(summary(entity), secret);
    }

    /** Durably disables one trigger. */
    public boolean delete(String triggerId) {
        return triggers.findByIdAndUserIdAndStatus(triggerId, currentUserId(), ACTIVE)
                .map(entity -> {
                    entity.setStatus("CANCELLED");
                    triggers.save(entity);
                    return true;
                })
                .orElse(false);
    }

    /** Called inside workflow deletion's transaction so no orphan trigger remains active. */
    public int cancelForWorkflow(String workflowId, long userId) {
        List<WorkflowWebhookTriggerEntity> active =
                triggers.findByWorkflowIdAndUserIdAndStatus(workflowId, userId, ACTIVE);
        active.forEach(entity -> entity.setStatus("CANCELLED"));
        if (!active.isEmpty()) triggers.saveAll(active);
        return active.size();
    }

    /** Authenticates, optionally deduplicates, and submits one webhook delivery. */
    public DeliveryResult deliver(String triggerId, String secret, String eventId,
                                  Map<String, Object> incomingInputs) {
        WorkflowWebhookTriggerEntity trigger = authenticate(triggerId, secret);
        if (isIsolationWeakened(trigger)) {
            trigger.setLastError(WEAKENED_ISOLATION);
            triggers.save(trigger);
            throw new WorkflowWebhookUnavailableException(WEAKENED_ISOLATION);
        }

        Map<String, Object> inputs = parseInputs(trigger.getDefaultInputsJson());
        inputs.putAll(copyInputs(incomingInputs));
        // Reject invalid/missing inputs before consuming an idempotency key.
        workflows.compile(trigger.getWorkflowId(), inputs, true);

        ClaimResult claimed = claim(triggerId, eventId);
        if (!claimed.newlyClaimed()) {
            return deliveryResult(triggerId, claimed.delivery(), true);
        }
        WorkflowWebhookDeliveryEntity claim = claimed.delivery();
        CountDownLatch admissionReady = new CountDownLatch(1);
        AtomicBoolean admitted = new AtomicBoolean();

        try {
            BackgroundTaskRegistry.Task task = tasks.submit(trigger.getUserId(),
                    BackgroundTaskRegistry.Priority.INTERACTIVE, "workflow-webhook",
                    "webhook " + trigger.getName(), runningTask -> {
                        admissionReady.await();
                        if (!admitted.get()) {
                            throw new IllegalStateException("Webhook delivery admission failed");
                        }
                        if (runningTask.cancelRequested()) {
                            finishDelivery(claim.getId(), claim.getTaskId(), "CANCELLED",
                                    "Webhook delivery was cancelled before execution.");
                            throw new IllegalStateException(
                                    "Webhook delivery cancelled before execution");
                        }
                        try {
                            markDeliverySubmitted(claim.getId(), claim.getTaskId());
                            AiPermissionContext.set(permissionMode(trigger.getPermissionMode()));
                            AgentRun run;
                            try {
                                run = executions.startForAi(trigger.getWorkflowId(), inputs);
                            } finally {
                                AiPermissionContext.clear();
                            }
                            runningTask.onCancel(() -> {
                                run.markCancelled();
                                run.approve(null);
                            });
                            String output = executions.waitForAiRun(run);
                            finishDelivery(claim.getId(), claim.getTaskId(), "COMPLETED", null);
                            return output;
                        } catch (Exception failure) {
                            String status = runningTask.cancelRequested() ? "CANCELLED" : "FAILED";
                            finishDelivery(claim.getId(), claim.getTaskId(), status,
                                    errorMessage(failure));
                            throw failure;
                        }
                    });

            claim.setTaskId(task.id());
            claim.setStatus("QUEUED");
            task.onCancel(() -> finishDelivery(claim.getId(), task.id(), "CANCELLED",
                    "Webhook delivery was cancelled while queued."));
            deliveries.save(claim);
            trigger.setFires(trigger.getFires() + 1);
            trigger.setLastFireAt(Instant.now());
            trigger.setLastTaskId(task.id());
            trigger.setLastError(null);
            triggers.save(trigger);
            admitted.set(true);
            try {
                trimDeliveries(triggerId);
            } catch (RuntimeException retentionFailure) {
                log.warn("webhook {} delivery retention failed: {}",
                        triggerId, errorMessage(retentionFailure));
            }
            return new DeliveryResult(triggerId, task.id(), true, false,
                    claim.getStatus(), null);
        } catch (BackgroundTaskCapacityException capacity) {
            // Nothing was admitted and no workflow side effect began, so release the idempotency
            // claim. The caller receives 429 + Retry-After and may safely retry the same event ID.
            try {
                deliveries.deleteById(claim.getId());
                deliveries.flush();
            } catch (RuntimeException releaseFailure) {
                String message = "Background queue was full and the webhook claim could not be "
                        + "released safely: " + errorMessage(releaseFailure);
                recordAdmissionFailure(claim, message);
                persistTriggerError(trigger, message);
                throw new IllegalStateException(message, releaseFailure);
            }
            persistTriggerError(trigger, errorMessage(capacity));
            throw capacity;
        } catch (RuntimeException failure) {
            String message = errorMessage(failure);
            if (!admitted.get()) {
                recordAdmissionFailure(claim, message);
            }
            persistTriggerError(trigger, message);
            throw failure;
        } finally {
            admissionReady.countDown();
        }
    }

    private void recordAdmissionFailure(WorkflowWebhookDeliveryEntity claim, String message) {
        claim.setStatus("FAILED");
        claim.setCompletedAt(Instant.now());
        claim.setError(truncate(message, 4_000));
        try {
            deliveries.save(claim);
        } catch (RuntimeException persistenceFailure) {
            log.warn("webhook delivery {} admission failure not persisted: {}",
                    claim.getId(), errorMessage(persistenceFailure));
        }
    }

    private void persistTriggerError(WorkflowWebhookTriggerEntity trigger, String message) {
        trigger.setLastError(message);
        try {
            triggers.save(trigger);
        } catch (RuntimeException persistenceFailure) {
            log.warn("webhook {} admission error not persisted: {}",
                    trigger.getId(), errorMessage(persistenceFailure));
        }
    }

    public Map<String, Object> summary(WorkflowWebhookTriggerEntity entity) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("triggerId", entity.getId());
        out.put("workflowId", entity.getWorkflowId());
        out.put("name", entity.getName());
        out.put("endpoint", "/api/workflow-hooks/" + entity.getId());
        out.put("defaultInputKeys", new ArrayList<>(parseInputs(
                entity.getDefaultInputsJson()).keySet()));
        out.put("fires", entity.getFires());
        out.put("lastFireAt", string(entity.getLastFireAt()));
        out.put("lastTaskId", entity.getLastTaskId());
        out.put("lastError", entity.getLastError());
        out.put("createdAt", string(entity.getCreatedAt()));
        out.put("permissionMode", permissionMode(entity.getPermissionMode()).name());
        out.put("sandboxProfile", normalizeSandboxProfile(entity.getSandboxProfile()));
        out.put("persistent", true);
        return out;
    }

    /**
     * Performs the delivery credential check before a controller parses an untrusted payload.
     * {@link #deliver(String, String, String, Map)} deliberately authenticates again so callers
     * cannot accidentally bypass the check and a concurrent secret rotation still takes effect.
     */
    public void authenticateDelivery(String triggerId, String secret) {
        authenticate(triggerId, secret);
    }

    private WorkflowWebhookTriggerEntity authenticate(String triggerId, String secret) {
        if (triggerId == null || triggerId.isBlank() || secret == null || secret.isBlank()) {
            throw new WorkflowWebhookAuthenticationException();
        }
        WorkflowWebhookTriggerEntity entity = triggers.findById(triggerId)
                .filter(candidate -> ACTIVE.equals(candidate.getStatus()))
                .orElseThrow(WorkflowWebhookAuthenticationException::new);
        byte[] expected = entity.getSecretHash().getBytes(StandardCharsets.UTF_8);
        byte[] provided = hash(secret).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, provided)) {
            throw new WorkflowWebhookAuthenticationException();
        }
        return entity;
    }

    /** Every request is audited; {@code newlyClaimed=false} is a keyed concurrent/retried event. */
    private ClaimResult claim(String triggerId, String eventId) {
        boolean keyed = eventId != null && !eventId.isBlank();
        String eventHash;
        String id;
        if (keyed) {
            String safeEventId = eventId.strip();
            if (safeEventId.length() > MAX_EVENT_ID_CHARS) {
                throw new IllegalArgumentException("X-FengYu-Event-Id must be at most "
                        + MAX_EVENT_ID_CHARS + " characters");
            }
            eventHash = hash(safeEventId);
            id = triggerId + ":" + eventHash;
        } else {
            String nonce = UUID.randomUUID().toString().replace("-", "");
            eventHash = hash(nonce);
            id = triggerId + ":" + nonce;
        }
        WorkflowWebhookDeliveryEntity claim = new WorkflowWebhookDeliveryEntity();
        claim.setId(id);
        claim.setTriggerId(triggerId);
        claim.setEventHash(eventHash);
        claim.setIdempotencyKeyPresent(keyed);
        claim.setStatus("CLAIMED");
        claim.setAcceptedAt(Instant.now());
        try {
            return new ClaimResult(deliveries.saveAndFlush(claim), true);
        } catch (DataIntegrityViolationException duplicate) {
            return new ClaimResult(deliveries.findById(id).orElseThrow(() -> duplicate), false);
        }
    }

    private void trimDeliveries(String triggerId) {
        long count = deliveries.countByTriggerId(triggerId);
        if (count <= MAX_DELIVERIES_RETAINED) return;
        List<String> overflow = deliveries.findByTriggerIdOrderByAcceptedAtDesc(
                        triggerId, PageRequest.of(1, MAX_DELIVERIES_RETAINED)).stream()
                .map(WorkflowWebhookDeliveryEntity::getId)
                .toList();
        if (!overflow.isEmpty()) deliveries.deleteAllById(overflow);
    }

    private WorkflowWebhookTriggerEntity ownedActive(String triggerId) {
        return triggers.findByIdAndUserIdAndStatus(triggerId, currentUserId(), ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("Unknown webhook trigger: "
                        + triggerId));
    }

    private boolean isIsolationWeakened(WorkflowWebhookTriggerEntity trigger) {
        return "sandboxed".equals(normalizeSandboxProfile(trigger.getSandboxProfile()))
                && "unsandboxed".equals(currentSandboxProfile());
    }

    private String currentSandboxProfile() {
        return unsandboxedPlugins.getAsBoolean() ? "unsandboxed" : "sandboxed";
    }

    private long currentUserId() {
        Long userId = securityContext.currentUserId();
        if (userId == null) throw new IllegalStateException("No authenticated user");
        return userId;
    }

    private static DeliveryResult deliveryResult(String triggerId,
            WorkflowWebhookDeliveryEntity delivery, boolean duplicate) {
        boolean accepted = "QUEUED".equals(delivery.getStatus())
                || "SUBMITTED".equals(delivery.getStatus())
                || "COMPLETED".equals(delivery.getStatus());
        return new DeliveryResult(triggerId, delivery.getTaskId(),
                accepted, duplicate,
                delivery.getStatus(), delivery.getError());
    }

    private void finishDelivery(String deliveryId, String taskId, String status, String error) {
        try {
            String safeError = error == null ? null : truncate(error, 4_000);
            if (deliveries.finishIfActive(deliveryId, status, Instant.now(), safeError) == 0) {
                return;
            }
            WorkflowWebhookDeliveryEntity delivery = deliveries.findById(deliveryId).orElse(null);
            if (delivery == null) return;
            triggers.findById(delivery.getTriggerId())
                    .filter(trigger -> java.util.Objects.equals(taskId, trigger.getLastTaskId()))
                    .ifPresent(trigger -> {
                        trigger.setLastError("COMPLETED".equals(status) ? null : safeError);
                        triggers.save(trigger);
                    });
        } catch (RuntimeException persistenceFailure) {
            log.warn("webhook delivery {} completion not persisted: {}",
                    deliveryId, errorMessage(persistenceFailure));
        }
    }

    private void markDeliverySubmitted(String deliveryId, String taskId) {
        try {
            deliveries.markSubmittedIfQueued(deliveryId, taskId);
        } catch (RuntimeException persistenceFailure) {
            log.warn("webhook delivery {} queue release not persisted: {}",
                    deliveryId, errorMessage(persistenceFailure));
        }
    }

    private static Map<String, Object> deliverySummary(WorkflowWebhookDeliveryEntity delivery) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", delivery.getTaskId());
        out.put("status", delivery.getStatus());
        out.put("acceptedAt", string(delivery.getAcceptedAt()));
        out.put("completedAt", string(delivery.getCompletedAt()));
        out.put("error", delivery.getError());
        // Historical rows predate this nullable field and were necessarily event-keyed.
        out.put("idempotencyKeyPresent",
                !Boolean.FALSE.equals(delivery.getIdempotencyKeyPresent()));
        return out;
    }

    private static AiPermissionMode permissionMode(String raw) {
        try {
            return AiPermissionMode.valueOf(raw);
        } catch (RuntimeException malformed) {
            return AiPermissionMode.ASK_FOR_APPROVAL;
        }
    }

    private static void rejectEphemeralFileInputs(WorkflowDefinition definition) {
        Object propertiesValue = definition.inputSchema() == null
                ? null : definition.inputSchema().get("properties");
        if (!(propertiesValue instanceof Map<?, ?> properties)) return;
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> property)) continue;
            if ("fengyu-file".equals(property.get("format"))
                    || "shared-directory".equals(property.get("x-fengyu-auto"))) {
                throw new IllegalArgumentException("Webhook triggers cannot bind ephemeral file "
                        + "input '" + entry.getKey() + "'; use persistent plugin-managed data");
            }
        }
    }

    private static String normalizeSandboxProfile(String raw) {
        return "unsandboxed".equals(raw) ? "unsandboxed" : "sandboxed";
    }

    private static Map<String, Object> copyInputs(Map<String, Object> inputs) {
        if (inputs == null || inputs.isEmpty()) return new LinkedHashMap<>();
        return parseInputs(toJson(inputs));
    }

    private static Map<String, Object> parseInputs(String value) {
        try {
            return JSON.readValue(value == null || value.isBlank() ? "{}" : value, INPUT_MAP);
        } catch (Exception malformed) {
            throw new IllegalArgumentException("Webhook inputs are malformed", malformed);
        }
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception malformed) {
            throw new IllegalArgumentException("Webhook inputs cannot be serialized", malformed);
        }
    }

    private static String generateSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String string(Instant value) {
        return value == null ? null : value.toString();
    }

    private static String errorMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static String truncate(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "…";
    }

    public record CreatedTrigger(Map<String, Object> trigger, String secret) {}

    public record DeliveryResult(String triggerId, String taskId, boolean accepted,
                                 boolean duplicate, String deliveryStatus, String error) {}

    private record ClaimResult(WorkflowWebhookDeliveryEntity delivery, boolean newlyClaimed) {}
}
