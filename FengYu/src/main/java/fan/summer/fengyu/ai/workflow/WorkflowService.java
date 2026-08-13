package fan.summer.fengyu.ai.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.ai.agent.AgentPlan;
import fan.summer.fengyu.ai.agent.AgentStep;
import fan.summer.fengyu.database.entity.ai.WorkflowEntity;
import fan.summer.fengyu.database.repository.ai.WorkflowRepository;
import fan.summer.fengyu.security.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** CRUD, publication and input binding for reusable workflows. */
@Service
public class WorkflowService {
    private static final Pattern INPUT_REFERENCE =
            Pattern.compile("\\{\\{inputs\\.([A-Za-z0-9_.-]+)}}");
    private static final Map<String, Object> EMPTY_SCHEMA = Map.of(
            "type", "object", "properties", Map.of());

    private final WorkflowRepository workflows;
    private final SecurityContext securityContext;
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    public WorkflowService(WorkflowRepository workflows, SecurityContext securityContext) {
        this.workflows = workflows;
        this.securityContext = securityContext;
    }

    public List<WorkflowDefinition> list() {
        return workflows.findByUserIdOrderByUpdatedAtDesc(currentUserId()).stream()
                .map(this::toDefinition)
                .toList();
    }

    public WorkflowDefinition get(String id) {
        return toDefinition(entity(id));
    }

    public List<WorkflowDefinition> published() {
        return workflows.findByUserIdAndPublishedTrueOrderByUpdatedAtDesc(currentUserId()).stream()
                .map(this::toDefinition)
                .toList();
    }

    @Transactional
    public WorkflowDefinition create(WorkflowDraft draft) {
        validateDraft(draft);
        LocalDateTime now = LocalDateTime.now();
        WorkflowEntity entity = new WorkflowEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserId(currentUserId());
        entity.setCreatedAt(now);
        apply(entity, draft);
        entity.setUpdatedAt(now);
        return toDefinition(workflows.save(entity));
    }

    @Transactional
    public WorkflowDefinition update(String id, WorkflowDraft draft) {
        validateDraft(draft);
        WorkflowEntity entity = entity(id);
        apply(entity, draft);
        entity.setRevision(entity.getRevision() + 1);
        entity.setUpdatedAt(LocalDateTime.now());
        return toDefinition(workflows.save(entity));
    }

    @Transactional
    public WorkflowDefinition setPublished(String id, boolean published) {
        WorkflowEntity entity = entity(id);
        entity.setPublished(published);
        entity.setRevision(entity.getRevision() + 1);
        entity.setUpdatedAt(LocalDateTime.now());
        return toDefinition(workflows.save(entity));
    }

    @Transactional
    public void delete(String id) {
        workflows.delete(entity(id));
    }

    /** Bind runtime inputs into a fresh immutable plan without mutating the stored definition. */
    public AgentPlan compile(String id, Map<String, Object> inputs, boolean requirePublished) {
        WorkflowDefinition definition = get(id);
        if (requirePublished && !definition.published()) {
            throw new IllegalStateException("Workflow is not published: " + id);
        }
        Map<String, Object> safeInputs = inputs == null ? Map.of() : new LinkedHashMap<>(inputs);
        validateInputs(definition.inputSchema(), safeInputs);
        List<AgentStep> steps = new ArrayList<>();
        for (AgentStep step : definition.plan().steps()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = (Map<String, Object>) bindValue(step.args(), safeInputs);
            steps.add(new AgentStep(step.index(), step.toolName(), args, step.description(),
                    step.requiresApproval(), step.dependsOn()));
        }
        String goal = String.valueOf(bindValue(definition.plan().goal(), safeInputs));
        return new AgentPlan(goal, List.copyOf(steps),
                definition.plan().reasoning());
    }

    public String inputSchemaJson(WorkflowDefinition definition) {
        return write(definition.inputSchema());
    }

    private void apply(WorkflowEntity entity, WorkflowDraft draft) {
        entity.setName(draft.name().trim());
        entity.setDescription(draft.description() == null ? "" : draft.description().trim());
        entity.setInputSchemaJson(write(draft.inputSchema() == null ? EMPTY_SCHEMA : draft.inputSchema()));
        entity.setPlanJson(write(draft.plan()));
    }

    private void validateDraft(WorkflowDraft draft) {
        if (draft == null) throw new IllegalArgumentException("Workflow body is required");
        if (draft.name() == null || draft.name().isBlank()) {
            throw new IllegalArgumentException("Workflow name is required");
        }
        if (draft.name().trim().length() > 160) {
            throw new IllegalArgumentException("Workflow name must not exceed 160 characters");
        }
        if (draft.plan() == null || draft.plan().steps() == null) {
            throw new IllegalArgumentException("Workflow plan and steps are required");
        }
        for (int index = 0; index < draft.plan().steps().size(); index++) {
            AgentStep step = draft.plan().steps().get(index);
            if (step == null || step.index() != index) {
                throw new IllegalArgumentException("Workflow step indexes must be contiguous from 0");
            }
            if (step.toolName() != null && step.toolName().startsWith("run_workflow_")) {
                throw new IllegalArgumentException("Nested workflow tools are not supported yet");
            }
        }
        Object type = draft.inputSchema() == null ? "object" : draft.inputSchema().get("type");
        if (type != null && !"object".equals(type)) {
            throw new IllegalArgumentException("Workflow input schema must describe an object");
        }
    }

    @SuppressWarnings("unchecked")
    private void validateInputs(Map<String, Object> schema, Map<String, Object> inputs) {
        Object requiredValue = schema.get("required");
        if (requiredValue instanceof List<?> required) {
            for (Object name : required) {
                if (!inputs.containsKey(String.valueOf(name))) {
                    throw new IllegalArgumentException("Missing required workflow input: " + name);
                }
            }
        }
        Object propertiesValue = schema.get("properties");
        if (!(propertiesValue instanceof Map<?, ?> properties)) return;
        for (Map.Entry<String, Object> input : inputs.entrySet()) {
            Object propertyValue = properties.get(input.getKey());
            if (!(propertyValue instanceof Map<?, ?> property) || input.getValue() == null) continue;
            Object expected = property.get("type");
            if (expected != null && !matchesType(String.valueOf(expected), input.getValue())) {
                throw new IllegalArgumentException("Workflow input '" + input.getKey()
                        + "' must be " + expected);
            }
        }
    }

    private boolean matchesType(String type, Object value) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List<?>;
            case "object" -> value instanceof Map<?, ?>;
            default -> true;
        };
    }

    private Object bindValue(Object value, Map<String, Object> inputs) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> bound = new LinkedHashMap<>();
            map.forEach((key, child) -> bound.put(String.valueOf(key), bindValue(child, inputs)));
            return bound;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(child -> bindValue(child, inputs)).toList();
        }
        if (!(value instanceof String text)) return value;
        Matcher exact = INPUT_REFERENCE.matcher(text);
        if (exact.matches()) return requiredInput(inputs, exact.group(1));
        Matcher matcher = INPUT_REFERENCE.matcher(text);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            Object input = requiredInput(inputs, matcher.group(1));
            String replacement = input instanceof String string ? string : write(input);
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private Object requiredInput(Map<String, Object> inputs, String path) {
        String[] segments = path.split("\\.");
        Object value = inputs.get(segments[0]);
        if (value == null && !inputs.containsKey(segments[0])) {
            throw new IllegalArgumentException("No workflow input is available for " + path);
        }
        for (int i = 1; i < segments.length; i++) {
            if (!(value instanceof Map<?, ?> map) || !map.containsKey(segments[i])) {
                throw new IllegalArgumentException("No workflow input is available for " + path);
            }
            value = map.get(segments[i]);
        }
        return value;
    }

    private WorkflowEntity entity(String id) {
        return workflows.findByIdAndUserId(id, currentUserId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown workflow: " + id));
    }

    private WorkflowDefinition toDefinition(WorkflowEntity entity) {
        return new WorkflowDefinition(entity.getId(), entity.getName(), entity.getDescription(),
                readMap(entity.getInputSchemaJson()), read(entity.getPlanJson(), AgentPlan.class),
                entity.isPublished(), entity.getRevision(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private long currentUserId() {
        Long id = securityContext.currentUserId();
        if (id == null) throw new IllegalStateException("No authenticated user");
        return id;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalArgumentException("Workflow contains data that cannot be serialized", error);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception error) {
            throw new IllegalStateException("Could not read workflow definition", error);
        }
    }

    private Map<String, Object> readMap(String value) {
        try {
            return json.readValue(value, new TypeReference<>() {});
        } catch (Exception error) {
            throw new IllegalStateException("Could not read workflow input schema", error);
        }
    }

    public record WorkflowDraft(String name, String description,
                                Map<String, Object> inputSchema, AgentPlan plan) {
    }
}
