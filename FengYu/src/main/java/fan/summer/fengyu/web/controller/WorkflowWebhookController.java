package fan.summer.fengyu.web.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.ai.workflow.WorkflowWebhookTriggerService;
import fan.summer.fengyu.ai.tools.AiPermissionMode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Management and delivery REST surface for durable loopback webhook triggers. */
@RestController
public class WorkflowWebhookController {

    public static final String SECRET_HEADER = "X-FengYu-Webhook-Secret";
    public static final String EVENT_ID_HEADER = "X-FengYu-Event-Id";
    static final int MAX_PAYLOAD_BYTES = 256 * 1024;

    private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final TypeReference<Map<String, Object>> INPUT_MAP = new TypeReference<>() {};

    private final WorkflowWebhookTriggerService triggers;

    public WorkflowWebhookController(WorkflowWebhookTriggerService triggers) {
        this.triggers = triggers;
    }

    /** Token-authenticated trigger management. */
    @GetMapping("/api/agent/webhook-triggers")
    public List<Map<String, Object>> list() {
        return triggers.list();
    }

    @GetMapping("/api/agent/webhook-triggers/{triggerId}/deliveries")
    public List<Map<String, Object>> deliveries(@PathVariable String triggerId,
                                                @RequestParam(required = false) Integer limit) {
        return triggers.listDeliveries(triggerId, limit);
    }

    /** The plaintext secret is returned once and never appears in later list responses. */
    @PostMapping("/api/agent/webhook-triggers")
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest request) {
        if (request == null) throw new IllegalArgumentException("Request body is required");
        WorkflowWebhookTriggerService.CreatedTrigger created = triggers.create(
                request.workflowId(), request.name(), request.defaultInputs(),
                request.permissionMode());
        return ResponseEntity.status(HttpStatus.CREATED).body(createdResponse(created));
    }

    /** Rotating invalidates the old secret immediately and returns the replacement once. */
    @PostMapping("/api/agent/webhook-triggers/{triggerId}/rotate-secret")
    public Map<String, Object> rotateSecret(@PathVariable String triggerId) {
        return createdResponse(triggers.rotateSecret(triggerId));
    }

    @DeleteMapping("/api/agent/webhook-triggers/{triggerId}")
    public Map<String, Object> delete(@PathVariable String triggerId) {
        return Map.of("ok", triggers.delete(triggerId), "triggerId", triggerId);
    }

    /**
     * Secret-authenticated delivery endpoint. The launch token filter deliberately bypasses only
     * POSTs under this path; this method's independent secret check is therefore mandatory.
     */
    @PostMapping("/api/workflow-hooks/{triggerId}")
    public ResponseEntity<WorkflowWebhookTriggerService.DeliveryResult> deliver(
            @PathVariable String triggerId,
            @RequestHeader(name = SECRET_HEADER, required = false) String secret,
            @RequestHeader(name = EVENT_ID_HEADER, required = false) String eventId,
            @RequestBody(required = false) byte[] payload) {
        // Reject unknown credentials before spending work on or revealing details about a body.
        triggers.authenticateDelivery(triggerId, secret);
        Map<String, Object> inputs = parsePayload(payload);
        WorkflowWebhookTriggerService.DeliveryResult result =
                triggers.deliver(triggerId, secret, eventId, inputs);
        return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(result);
    }

    private static Map<String, Object> createdResponse(
            WorkflowWebhookTriggerService.CreatedTrigger created) {
        Map<String, Object> response = new LinkedHashMap<>(created.trigger());
        response.put("secret", created.secret());
        response.put("secretHeader", SECRET_HEADER);
        response.put("eventIdHeader", EVENT_ID_HEADER);
        return response;
    }

    private static Map<String, Object> parsePayload(byte[] payload) {
        if (payload == null || payload.length == 0) return Map.of();
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Webhook JSON payload exceeds "
                    + MAX_PAYLOAD_BYTES + " bytes");
        }
        try {
            return JSON.readValue(payload, INPUT_MAP);
        } catch (Exception malformed) {
            throw new IllegalArgumentException("Webhook payload must be a JSON object", malformed);
        }
    }

    public record CreateRequest(String workflowId, String name,
                                Map<String, Object> defaultInputs,
                                AiPermissionMode permissionMode) {}
}
