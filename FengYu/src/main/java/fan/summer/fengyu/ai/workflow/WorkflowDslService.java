package fan.summer.fengyu.ai.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.ai.agent.AgentPlan;
import fan.summer.fengyu.ai.agent.AgentStep;
import fan.summer.fengyu.ai.config.AiToolRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code .fyf} Flow interchange document: what export writes and import (re)creates.
 *
 * <p>A {@code .fyf} file is a small envelope around the pieces a canvas needs. Two shapes
 * exist, decided at export time by what the definition actually carries:
 *
 * <ul>
 *   <li><b>graph documents</b> — {@code graph} (the canvas source of truth, round-trips
 *       through the builder) plus layout. The compiled {@code plan} is deliberately NOT
 *       stored: it is derived state, and a hand-edited or older-schema file must never ship
 *       a plan that disagrees with its graph. The builder recompiles on apply.</li>
 *   <li><b>plan documents</b> — legacy definitions saved before the graph column carried
 *       content; the plan is the only executable source, so it is embedded and import
 *       creates from it directly.</li>
 * </ul>
 *
 * <p>Import here accepts the <b>plan</b> shape (API callers have no compiler); graph-only
 * documents are applied through the builder, which owns graph→plan compilation. Tool names
 * the live catalog does not know are reported back as {@code missingTools} rather than
 * rejected — a flow shared between machines should import and show its gaps, not bounce.</p>
 */
@Service
public class WorkflowDslService {
    public static final String FORMAT = "fengyu-flow";
    public static final int SCHEMA_VERSION = 1;
    private static final String GENERATOR = "FengYu";
    private static final Map<String, Object> EMPTY_SCHEMA = Map.of(
            "type", "object", "properties", Map.of());

    private final WorkflowService workflows;
    private final ObjectProvider<AiToolRegistry> toolRegistry;
    private final ObjectMapper json = JsonMapper.builder()
            .findAndAddModules()
            // Hand-edited .fyf files may carry keys this build does not know — an unknown
            // annotation must not bounce a document whose plan is perfectly valid.
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    public WorkflowDslService(WorkflowService workflows, ObjectProvider<AiToolRegistry> toolRegistry) {
        this.workflows = workflows;
        this.toolRegistry = toolRegistry;
    }

    /** Result of {@link #importDsl(Map)}: the created definition plus its catalog gaps. */
    public record DslImportResult(WorkflowDefinition workflow, List<String> missingTools) {}

    public Map<String, Object> export(String id) {
        WorkflowDefinition definition = workflows.get(id);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", FORMAT);
        document.put("schemaVersion", SCHEMA_VERSION);
        document.put("generator", GENERATOR);
        document.put("name", definition.name());
        document.put("description", definition.description());
        document.put("goal", definition.plan().goal());
        document.put("inputSchema", definition.inputSchema() == null ? EMPTY_SCHEMA : definition.inputSchema());
        if (definition.graph() != null && !definition.graph().isEmpty()) {
            document.put("graph", definition.graph());
        } else {
            document.put("plan", definition.plan());
        }
        if (definition.layout() != null && !definition.layout().isEmpty()) {
            document.put("layout", definition.layout());
        }
        return document;
    }

    public DslImportResult importDsl(Map<String, Object> document) {
        if (document == null) throw new IllegalArgumentException("A .fyf document body is required");
        if (!FORMAT.equals(document.get("format"))) {
            throw new IllegalArgumentException(
                    "Not a FengYu Flow (.fyf) document: expected format \"" + FORMAT + "\"");
        }
        Object version = document.get("schemaVersion");
        if (!(version instanceof Number number) || number.intValue() != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported .fyf schemaVersion: " + version
                    + " (this build reads version " + SCHEMA_VERSION + ")");
        }
        if (!(document.get("plan") instanceof Map<?, ?> planMap)) {
            throw new IllegalArgumentException("This .fyf document carries no compiled plan. "
                    + "Re-export it from a saved Flow, or import it through the Flow builder, "
                    + "which compiles the canvas graph.");
        }
        String name = document.get("name") instanceof String value && !value.isBlank()
                ? value.trim() : "Imported Flow";
        String description = document.get("description") instanceof String value ? value : "";
        Map<String, Object> inputSchema = document.get("inputSchema") instanceof Map<?, ?> schema
                ? stringKeyMap(schema) : EMPTY_SCHEMA;
        Map<String, WorkflowDefinition.NodeLayout> layout =
                document.get("layout") instanceof Map<?, ?> rawLayout
                        ? json.convertValue(rawLayout,
                                new TypeReference<Map<String, WorkflowDefinition.NodeLayout>>() {})
                        : Map.of();
        Map<String, Object> graph = document.get("graph") instanceof Map<?, ?> rawGraph
                ? stringKeyMap(rawGraph) : null;
        AgentPlan plan = json.convertValue(planMap, AgentPlan.class);
        WorkflowDefinition created = workflows.create(
                new WorkflowService.WorkflowDraft(name, description, inputSchema, plan, layout, graph, null));
        return new DslImportResult(created, missingTools(plan));
    }

    /** Plan tool names the live catalog cannot resolve, in step order, deduplicated. */
    private List<String> missingTools(AgentPlan plan) {
        AiToolRegistry registry = toolRegistry.getIfAvailable();
        if (registry == null) return List.of();
        Set<String> available = new LinkedHashSet<>();
        for (AiToolRegistry.ToolDescriptor descriptor : registry.descriptors("en")) {
            if (descriptor.name() != null) available.add(descriptor.name());
        }
        List<String> missing = new ArrayList<>();
        for (AgentStep step : plan.steps()) {
            if (step.toolName() != null && !available.contains(step.toolName())) {
                if (missing.stream().noneMatch(step.toolName()::equals)) missing.add(step.toolName());
            }
        }
        return List.copyOf(missing);
    }

    /** Download filename for an exported document: a filesystem-safe slug of the flow name. */
    public static String downloadFilename(String name) {
        String slug = (name == null ? "" : name)
                .replaceAll("[\\s_]+", "-")
                .replaceAll("[^A-Za-z0-9-]", "")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-+|-+$", "")
                .toLowerCase();
        return (slug.isBlank() ? "flow" : slug) + ".fyf";
    }

    private static Map<String, Object> stringKeyMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
