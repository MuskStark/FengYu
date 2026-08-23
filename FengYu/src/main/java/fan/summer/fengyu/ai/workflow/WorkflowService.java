package fan.summer.fengyu.ai.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.ai.agent.AgentPlan;
import fan.summer.fengyu.ai.agent.AgentStep;
import fan.summer.fengyu.database.entity.ai.WorkflowEntity;
import fan.summer.fengyu.database.entity.ai.WorkflowRevisionEntity;
import fan.summer.fengyu.database.repository.ai.WorkflowRepository;
import fan.summer.fengyu.database.repository.ai.WorkflowRevisionRepository;
import fan.summer.fengyu.security.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final int MAX_STEPS = 64;
    private static final int MAX_GRAPH_NODES = 512;
    private static final int MAX_GRAPH_EDGES = 1024;
    /** Pinned results ride inside plan_json; cap them so one debug pin can't bloat a row. */
    private static final int MAX_PINNED_RESULT_CHARS = 64 * 1024;

    private final WorkflowRepository workflows;
    private final WorkflowRevisionRepository revisions;
    private final SecurityContext securityContext;
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    public WorkflowService(WorkflowRepository workflows, WorkflowRevisionRepository revisions,
                           SecurityContext securityContext) {
        this.workflows = workflows;
        this.revisions = revisions;
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
                .map(this::toPublishedDefinition)
                .toList();
    }

    public List<WorkflowRevisionSummary> revisions(String id) {
        WorkflowEntity entity = entity(id);
        List<WorkflowRevisionSummary> history = new ArrayList<>(revisions
                .findByWorkflowIdAndUserIdOrderByRevisionDesc(id, currentUserId()).stream()
                .map(snapshot -> revisionSummary(entity, snapshot))
                .toList());
        // Legacy published rows predate the history table. Surface the current definition as
        // their active revision until the first edit/publish persists it as a real snapshot.
        if (entity.isPublished() && entity.getPublishedRevision() == null) {
            history.addFirst(new WorkflowRevisionSummary(entity.getRevision(), entity.getName(),
                    entity.getDescription(), entity.getUpdatedAt(), true));
        }
        return List.copyOf(history);
    }

    public WorkflowDefinition revision(String id, int revision) {
        WorkflowEntity entity = entity(id);
        if (entity.isPublished() && entity.getPublishedRevision() == null
                && entity.getRevision() == revision) {
            return legacyPublishedDefinition(entity);
        }
        WorkflowRevisionEntity snapshot = revisionEntity(entity, revision);
        return toPublishedDefinition(entity, snapshot);
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
        WorkflowEntity entity = entity(id);
        verifyRevision(entity, draft == null ? null : draft.expectedRevision());
        validateDraft(draft);
        ensureLegacyPublishedSnapshot(entity);
        apply(entity, draft);
        entity.setRevision(entity.getRevision() + 1);
        entity.setUpdatedAt(LocalDateTime.now());
        return toDefinition(workflows.save(entity));
    }

    private void verifyRevision(WorkflowEntity entity, Integer expectedRevision) {
        if (expectedRevision == null) return;
        if (expectedRevision != entity.getRevision()) {
            throw new WorkflowRevisionConflictException(entity.getId(),
                    expectedRevision, entity.getRevision());
        }
    }

    @Transactional
    public WorkflowDefinition setPublished(String id, boolean published) {
        return setPublished(id, published, null);
    }

    @Transactional
    public WorkflowDefinition setPublished(String id, boolean published, Integer expectedRevision) {
        WorkflowEntity entity = entity(id);
        verifyRevision(entity, expectedRevision);
        if (!published) ensureLegacyPublishedSnapshot(entity);
        int nextRevision = entity.getRevision() + 1;
        entity.setRevision(nextRevision);
        LocalDateTime now = LocalDateTime.now();
        if (published) {
            revisions.save(snapshot(entity, nextRevision, now));
            entity.setPublishedRevision(nextRevision);
        }
        entity.setPublished(published);
        entity.setUpdatedAt(now);
        return toDefinition(workflows.save(entity));
    }

    @Transactional
    public WorkflowDefinition restore(String id, int revision, Integer expectedRevision) {
        WorkflowEntity entity = entity(id);
        verifyRevision(entity, expectedRevision);
        ensureLegacyPublishedSnapshot(entity);
        WorkflowRevisionEntity snapshot = revisionEntity(entity, revision);
        applySnapshot(entity, snapshot);
        entity.setRevision(entity.getRevision() + 1);
        entity.setUpdatedAt(LocalDateTime.now());
        return toDefinition(workflows.save(entity));
    }

    @Transactional
    public void delete(String id) {
        WorkflowEntity entity = entity(id);
        revisions.deleteByWorkflowIdAndUserId(id, currentUserId());
        workflows.delete(entity);
    }

    /** Bind runtime inputs into a fresh immutable plan without mutating the stored definition. */
    public AgentPlan compile(String id, Map<String, Object> inputs, boolean requirePublished) {
        WorkflowEntity entity = entity(id);
        WorkflowDefinition definition = requirePublished
                ? toPublishedDefinition(entity)
                : toDefinition(entity);
        Map<String, Object> safeInputs = inputs == null ? Map.of() : new LinkedHashMap<>(inputs);
        validateInputs(definition.inputSchema(), safeInputs);
        List<AgentStep> steps = new ArrayList<>();
        for (AgentStep step : definition.plan().steps()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = (Map<String, Object>) bindValue(step.args(), safeInputs);
            steps.add(new AgentStep(step.index(), step.toolName(), args, step.description(),
                    step.requiresApproval(), step.dependsOn(), step.pinnedResult(), step.runWhen(),
                    step.retryPolicy(), step.outputBindings()));
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
        entity.setLayoutJson(write(draft.layout() == null ? Map.of() : draft.layout()));
        entity.setGraphJson(draft.graph() == null || draft.graph().isEmpty()
                ? null : write(draft.graph()));
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
        if (draft.plan().steps().size() > MAX_STEPS) {
            throw new IllegalArgumentException("Workflow must not exceed " + MAX_STEPS + " steps");
        }
        for (int index = 0; index < draft.plan().steps().size(); index++) {
            AgentStep step = draft.plan().steps().get(index);
            if (step == null || step.index() != index) {
                throw new IllegalArgumentException("Workflow step indexes must be contiguous from 0");
            }
            if (step.toolName() != null && step.toolName().startsWith("run_workflow_")) {
                throw new IllegalArgumentException("Nested workflow tools are not supported yet");
            }
            if (step.pinnedResult() != null && step.pinnedResult().length() > MAX_PINNED_RESULT_CHARS) {
                throw new IllegalArgumentException("Workflow step " + index
                        + " pins a result larger than " + MAX_PINNED_RESULT_CHARS + " characters");
            }
            java.util.Set<String> bindingNames = new java.util.HashSet<>();
            for (AgentStep.OutputBinding binding : step.outputBindings()) {
                if (!bindingNames.add(binding.name())) {
                    throw new IllegalArgumentException("Workflow step " + index
                            + " declares duplicate output binding '" + binding.name() + "'");
                }
            }
            validateRetryPolicy(step, index);
        }
        Object type = draft.inputSchema() == null ? "object" : draft.inputSchema().get("type");
        if (type != null && !"object".equals(type)) {
            throw new IllegalArgumentException("Workflow input schema must describe an object");
        }
        validateInputReferences(draft);
        validateLayout(draft.layout(), draft.plan().steps().size());
        validateGraph(draft.graph());
    }

    private static void validateRetryPolicy(AgentStep step, int index) {
        AgentStep.RetryPolicy retry = step.retryPolicy();
        if (retry.maxAttempts() < 1 || retry.maxAttempts() > 5) {
            throw new IllegalArgumentException("Workflow step " + index
                    + " maxAttempts must be between 1 and 5");
        }
        if (retry.backoffMs() < 0 || retry.backoffMs() > 30_000) {
            throw new IllegalArgumentException("Workflow step " + index
                    + " backoffMs must be between 0 and 30000");
        }
    }

    /**
     * The graph is editor metadata: it must be shaped like {@code {nodes: [...], edges: [...]}}
     * within sane caps so a corrupt client cannot store unbounded payloads. The backend never
     * interprets node internals — the flow builder owns that contract.
     */
    private void validateGraph(Map<String, Object> graph) {
        if (graph == null) return;
        Object nodes = graph.get("nodes");
        Object edges = graph.get("edges");
        if (nodes != null && !(nodes instanceof List<?>)) {
            throw new IllegalArgumentException("Workflow graph nodes must be a list");
        }
        if (edges != null && !(edges instanceof List<?>)) {
            throw new IllegalArgumentException("Workflow graph edges must be a list");
        }
        if (nodes instanceof List<?> list && list.size() > MAX_GRAPH_NODES) {
            throw new IllegalArgumentException("Workflow graph must not exceed "
                    + MAX_GRAPH_NODES + " nodes");
        }
        if (edges instanceof List<?> list && list.size() > MAX_GRAPH_EDGES) {
            throw new IllegalArgumentException("Workflow graph must not exceed "
                    + MAX_GRAPH_EDGES + " edges");
        }
    }

    /**
     * Fails a save (not a later run) when a step or the goal references an input the schema
     * never declares — a graph that could only ever fail at binding time. Only fields the
     * compiler actually binds are checked (goal + step args), so prose descriptions may
     * legitimately mention the template syntax.
     */
    private void validateInputReferences(WorkflowDraft draft) {
        Set<String> declared = new LinkedHashSet<>();
        Object properties = draft.inputSchema() == null ? null : draft.inputSchema().get("properties");
        if (properties instanceof Map<?, ?> map) {
            for (Object key : map.keySet()) declared.add(String.valueOf(key));
        }
        List<String> missing = new ArrayList<>();
        if (draft.plan().goal() instanceof String goal) {
            collectMissingInputs(goal, declared, missing);
        }
        for (AgentStep step : draft.plan().steps()) {
            if (step == null) continue;
            collectMissingInputs(step.args(), declared, missing);
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Workflow references undeclared input(s): "
                    + String.join(", ", new LinkedHashSet<>(missing)));
        }
    }

    private void collectMissingInputs(Object value, Set<String> declared, List<String> missing) {
        if (value instanceof Map<?, ?> map) {
            for (Object child : map.values()) collectMissingInputs(child, declared, missing);
        } else if (value instanceof List<?> list) {
            for (Object child : list) collectMissingInputs(child, declared, missing);
        } else if (value instanceof String text) {
            Matcher matcher = INPUT_REFERENCE.matcher(text);
            while (matcher.find()) {
                String root = matcher.group(1).split("\\.")[0];
                if (!declared.contains(root)) missing.add(root);
            }
        }
    }

    /** Layout is optional metadata; when present it must address real steps with finite coordinates. */
    private void validateLayout(Map<String, WorkflowDefinition.NodeLayout> layout, int stepCount) {
        if (layout == null || layout.isEmpty()) return;
        for (Map.Entry<String, WorkflowDefinition.NodeLayout> entry : layout.entrySet()) {
            int index;
            try {
                index = Integer.parseInt(entry.getKey());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Workflow layout key is not a step index: "
                        + entry.getKey());
            }
            if (index < 0 || index >= stepCount) {
                throw new IllegalArgumentException("Workflow layout references unknown step: "
                        + entry.getKey());
            }
            WorkflowDefinition.NodeLayout position = entry.getValue();
            if (position == null || !Double.isFinite(position.x()) || !Double.isFinite(position.y())) {
                throw new IllegalArgumentException("Workflow layout has an invalid position for step "
                        + entry.getKey());
            }
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

    private WorkflowRevisionEntity revisionEntity(WorkflowEntity entity, int revision) {
        return revisions.findByWorkflowIdAndUserIdAndRevision(
                        entity.getId(), currentUserId(), revision)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown workflow revision: " + entity.getId() + "@" + revision));
    }

    /** Persist the live content of an upgraded pre-history published row before it is edited. */
    private void ensureLegacyPublishedSnapshot(WorkflowEntity entity) {
        if (!entity.isPublished() || entity.getPublishedRevision() != null) return;
        LocalDateTime publishedAt = entity.getUpdatedAt() == null
                ? LocalDateTime.now() : entity.getUpdatedAt();
        revisions.save(snapshot(entity, entity.getRevision(), publishedAt));
        entity.setPublishedRevision(entity.getRevision());
    }

    private WorkflowRevisionEntity snapshot(WorkflowEntity entity, int revision,
                                            LocalDateTime publishedAt) {
        WorkflowRevisionEntity snapshot = new WorkflowRevisionEntity();
        snapshot.setId(entity.getId() + ":" + revision);
        snapshot.setWorkflowId(entity.getId());
        snapshot.setUserId(entity.getUserId());
        snapshot.setRevision(revision);
        snapshot.setName(entity.getName());
        snapshot.setDescription(entity.getDescription());
        snapshot.setInputSchemaJson(entity.getInputSchemaJson());
        snapshot.setPlanJson(entity.getPlanJson());
        snapshot.setLayoutJson(entity.getLayoutJson());
        snapshot.setGraphJson(entity.getGraphJson());
        snapshot.setPublishedAt(publishedAt);
        return snapshot;
    }

    private void applySnapshot(WorkflowEntity entity, WorkflowRevisionEntity snapshot) {
        entity.setName(snapshot.getName());
        entity.setDescription(snapshot.getDescription());
        entity.setInputSchemaJson(snapshot.getInputSchemaJson());
        entity.setPlanJson(snapshot.getPlanJson());
        entity.setLayoutJson(snapshot.getLayoutJson());
        entity.setGraphJson(snapshot.getGraphJson());
    }

    private WorkflowDefinition toDefinition(WorkflowEntity entity) {
        Integer publishedRevision = entity.getPublishedRevision();
        boolean hasUnpublishedChanges = entity.isPublished()
                && publishedRevision != null
                && publishedRevision != entity.getRevision();
        return new WorkflowDefinition(entity.getId(), entity.getName(), entity.getDescription(),
                readMap(entity.getInputSchemaJson()), read(entity.getPlanJson(), AgentPlan.class),
                readLayout(entity.getLayoutJson()), readMapOrEmpty(entity.getGraphJson()),
                entity.isPublished(), entity.getRevision(),
                publishedRevision, hasUnpublishedChanges,
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private WorkflowDefinition toPublishedDefinition(WorkflowEntity entity) {
        if (!entity.isPublished()) {
            throw new IllegalStateException("Workflow is not published: " + entity.getId());
        }
        if (entity.getPublishedRevision() == null) return legacyPublishedDefinition(entity);
        WorkflowRevisionEntity snapshot = revisions.findByWorkflowIdAndUserIdAndRevision(
                        entity.getId(), entity.getUserId(), entity.getPublishedRevision())
                .orElseThrow(() -> new IllegalStateException(
                        "Published workflow snapshot is missing: " + entity.getId()
                                + "@" + entity.getPublishedRevision()));
        return toPublishedDefinition(entity, snapshot);
    }

    private WorkflowDefinition legacyPublishedDefinition(WorkflowEntity entity) {
        return new WorkflowDefinition(entity.getId(), entity.getName(), entity.getDescription(),
                readMap(entity.getInputSchemaJson()), read(entity.getPlanJson(), AgentPlan.class),
                readLayout(entity.getLayoutJson()), readMapOrEmpty(entity.getGraphJson()),
                true, entity.getRevision(), entity.getRevision(), false,
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private WorkflowDefinition toPublishedDefinition(WorkflowEntity entity,
                                                     WorkflowRevisionEntity snapshot) {
        return new WorkflowDefinition(entity.getId(), snapshot.getName(), snapshot.getDescription(),
                readMap(snapshot.getInputSchemaJson()), read(snapshot.getPlanJson(), AgentPlan.class),
                readLayout(snapshot.getLayoutJson()), readMapOrEmpty(snapshot.getGraphJson()),
                true, snapshot.getRevision(), snapshot.getRevision(), false,
                entity.getCreatedAt(), snapshot.getPublishedAt());
    }

    private WorkflowRevisionSummary revisionSummary(WorkflowEntity entity,
                                                    WorkflowRevisionEntity snapshot) {
        return new WorkflowRevisionSummary(snapshot.getRevision(), snapshot.getName(),
                snapshot.getDescription(), snapshot.getPublishedAt(), entity.isPublished()
                        && Integer.valueOf(snapshot.getRevision()).equals(entity.getPublishedRevision()));
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

    private Map<String, WorkflowDefinition.NodeLayout> readLayout(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            Map<String, WorkflowDefinition.NodeLayout> layout =
                    json.readValue(value, new TypeReference<>() {});
            return layout == null ? Map.of() : layout;
        } catch (Exception error) {
            // Layout is presentational metadata — a corrupted one must never block the
            // definition from loading; the canvas falls back to its default grid.
            return Map.of();
        }
    }

    /** Graph is presentational like layout: a corrupted one falls back to plan reconstruction. */
    private Map<String, Object> readMapOrEmpty(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            Map<String, Object> graph = json.readValue(value, new TypeReference<>() {});
            return graph == null ? Map.of() : graph;
        } catch (Exception error) {
            return Map.of();
        }
    }

    public record WorkflowDraft(String name, String description,
                                Map<String, Object> inputSchema, AgentPlan plan,
                                Map<String, WorkflowDefinition.NodeLayout> layout,
                                Map<String, Object> graph,
                                Integer expectedRevision) {
        /** Backward-compatible constructor for older in-process callers. */
        public WorkflowDraft(String name, String description,
                             Map<String, Object> inputSchema, AgentPlan plan,
                             Map<String, WorkflowDefinition.NodeLayout> layout,
                             Map<String, Object> graph) {
            this(name, description, inputSchema, plan, layout, graph, null);
        }
    }
}
