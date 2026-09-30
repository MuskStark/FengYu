package fan.summer.fengyu.ai.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.ai.config.AiToolRegistry;
import fan.summer.fengyu.ai.tools.AuditedToolCallback;
import fan.summer.fengyu.ai.tools.ToolEffect;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Request-scoped tools used by the Flow builder chat.
 *
 * <p>The tools deliberately never mutate a workflow. {@code edit_current_flow} returns a
 * canonical proposal envelope that the builder previews and applies only after an explicit user
 * click. This keeps AI authoring inside the canvas' existing validation, optimistic-revision,
 * undo, and unsaved-draft boundaries instead of creating a second write path.</p>
 */
public final class FlowAuthoringToolFactory {
    private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Pattern INPUT_REFERENCE =
            Pattern.compile("\\{\\{inputs\\.([A-Za-z0-9_.-]+)}}");
    private static final Pattern NODE_REFERENCE = Pattern.compile(
            "\\{\\{node\\.([A-Za-z][A-Za-z0-9_-]*)\\.(?:input|result)(?:\\.|\\[|}})");
    private static final int MAX_STEPS = 64;
    private static final int MAX_NOTES = 128;

    private static final String EMPTY_INPUT_SCHEMA = """
            {"type":"object","properties":{},"additionalProperties":false}
            """;

    private static final String EDIT_INPUT_SCHEMA = """
            {
              "type":"object",
              "additionalProperties":false,
              "required":["name","goal","inputSchema","nodes","edges"],
              "properties":{
                "name":{"type":"string","minLength":1,"maxLength":160},
                "description":{"type":"string"},
                "goal":{"type":"string","minLength":1},
                "summary":{"type":"string","description":"Short user-facing summary of the proposed change."},
                "inputSchema":{"type":"object","description":"JSON Schema for the Flow run-time input object."},
                "nodes":{"type":"array","maxItems":64,"items":{"type":"object","additionalProperties":false,
                  "required":["id","toolName","args"],"properties":{
                    "id":{"type":"string","pattern":"^[A-Za-z][A-Za-z0-9_-]{0,63}$"},
                    "toolName":{"type":"string"},
                    "args":{"type":"object","description":"Tool arguments. Use {{inputs.name}} for Flow inputs and {{node.NODE_ID.result.path}} for upstream results."},
                    "title":{"type":"string"},"description":{"type":"string"},
                    "requiresApproval":{"type":"boolean"},
                    "x":{"type":"number"},"y":{"type":"number"}
                  }}},
                "edges":{"type":"array","maxItems":1024,"items":{"type":"object","additionalProperties":false,
                  "required":["source","target"],"properties":{
                    "source":{"type":"string"},"target":{"type":"string"},
                    "sourceHandle":{"type":"string","description":"Branch output such as true or false; omit for a normal dependency."}
                  }}}
              }
            }
            """;

    private FlowAuthoringToolFactory() {
    }

    /** Builds a fresh immutable tool set for one chat turn. */
    public static List<ToolCallback> create(Map<String, Object> rawContext,
                                            List<AiToolRegistry.ToolDescriptor> descriptors) {
        Map<String, Object> context = sanitizeContext(rawContext);
        List<Map<String, Object>> catalog = authoringCatalog(descriptors);
        return List.of(
                callback("inspect_current_flow", inspectDescription(), EMPTY_INPUT_SCHEMA,
                        ignored -> write(Map.of(
                                "kind", "flow_inspection",
                                "context", context,
                                "availableTools", catalog))),
                callback("diagnose_current_flow", diagnoseDescription(), EMPTY_INPUT_SCHEMA,
                        ignored -> write(diagnosticEnvelope(context, catalog))),
                callback("edit_current_flow", editDescription(), EDIT_INPUT_SCHEMA,
                        input -> propose(context, catalog, input)));
    }

    private static ToolCallback callback(String name, String description, String schema,
                                         java.util.function.Function<String, String> action) {
        return new AuditedToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder()
                    .name(name).description(description).inputSchema(schema).build();

            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public ToolEffect effect() { return ToolEffect.READ; }
            @Override public String call(String input) { return action.apply(input == null ? "{}" : input); }
        };
    }

    private static String inspectDescription() {
        return "Inspect the CURRENT FengYu Flow canvas and the exact tools available to it. "
                + "Call this before answering questions about the Flow or before editing it. "
                + "The result includes the live, possibly unsaved graph, input schema, current "
                + "diagnostics, and each tool's input/output contract.";
    }

    private static String diagnoseDescription() {
        return "Run deterministic diagnostics on the CURRENT FengYu Flow canvas. Use this when "
                + "the user asks why a Flow cannot save, publish, or run, or when a node failed. "
                + "The result combines live canvas issues with unavailable tools, missing required "
                + "arguments, bad references, dangling edges, and cycle checks. It does not modify the Flow.";
    }

    private static String editDescription() {
        return "Create or edit the CURRENT FengYu Flow as a complete replacement proposal. First "
                + "call inspect_current_flow and reuse existing node ids for nodes that remain. Use only "
                + "available toolName values and satisfy their required arguments. References to Flow "
                + "inputs use {{inputs.name}}; references to earlier nodes use "
                + "{{node.NODE_ID.result.path}}. Return every executable node and dependency edge, not "
                + "only the changed subset. This tool NEVER writes: it returns a diff-ready proposal "
                + "that FengYu shows to the user, who must explicitly apply it.";
    }

    private static String propose(Map<String, Object> context, List<Map<String, Object>> catalog,
                                  String input) {
        try {
            Map<String, Object> request = JSON.readValue(input, new TypeReference<>() {});
            Map<String, Object> proposal = normalizeProposal(context, catalog, request);
            return write(proposal);
        } catch (IllegalArgumentException error) {
            return write(Map.of("kind", "flow_proposal_error", "error", error.getMessage()));
        } catch (Exception error) {
            return write(Map.of("kind", "flow_proposal_error",
                    "error", "Invalid edit_current_flow arguments: " + error.getMessage()));
        }
    }

    private static Map<String, Object> normalizeProposal(Map<String, Object> context,
                                                          List<Map<String, Object>> catalog,
                                                          Map<String, Object> request) {
        String name = requiredText(request, "name");
        if (name.length() > 160) throw new IllegalArgumentException("Flow name exceeds 160 characters");
        String goal = requiredText(request, "goal");
        String description = text(request.get("description"));
        Map<String, Object> inputSchema = object(request.get("inputSchema"), "inputSchema");
        Object schemaType = inputSchema.get("type");
        if (schemaType != null && !"object".equals(schemaType)) {
            throw new IllegalArgumentException("inputSchema must describe an object");
        }

        Map<String, Map<String, Object>> tools = new LinkedHashMap<>();
        for (Map<String, Object> item : catalog) tools.put(text(item.get("name")), item);

        List<?> rawNodes = list(request.get("nodes"), "nodes");
        if (rawNodes.isEmpty()) throw new IllegalArgumentException("A Flow proposal needs at least one tool node");
        if (rawNodes.size() > MAX_STEPS) throw new IllegalArgumentException("A Flow must not exceed 64 tool nodes");
        List<Map<String, Object>> graphNodes = new ArrayList<>();
        Set<String> nodeIds = new LinkedHashSet<>();
        Map<String, String> nodeArguments = new LinkedHashMap<>();
        int index = 0;
        for (Object raw : rawNodes) {
            Map<String, Object> node = object(raw, "nodes[" + index + "]");
            String id = requiredText(node, "id");
            if (!SAFE_ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid node id: " + id);
            if ("start".equals(id)) throw new IllegalArgumentException(
                    "Node id 'start' is reserved for the structural Start node");
            if (!nodeIds.add(id)) throw new IllegalArgumentException("Duplicate node id: " + id);
            String toolName = requiredText(node, "toolName");
            Map<String, Object> tool = tools.get(toolName);
            if (tool == null) throw new IllegalArgumentException("Unavailable Flow tool: " + toolName);
            Map<String, Object> args = object(node.get("args"), "arguments for " + id);
            requireToolArguments(id, args, text(tool.get("inputSchema")));
            nodeArguments.put(id, write(args));
            double x = number(node.get("x"), 280d * (index % 4));
            double y = number(node.get("y"), 100d + 180d * (index / 4));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("toolName", toolName);
            data.put("argsText", write(args));
            data.put("description", firstNonBlank(text(node.get("description")), text(tool.get("description")), toolName));
            data.put("requiresApproval", Boolean.TRUE.equals(node.get("requiresApproval")));
            if (!text(node.get("title")).isBlank()) data.put("title", text(node.get("title")));
            graphNodes.add(Map.of(
                    "id", id,
                    "type", "tool",
                    "position", Map.of("x", x, "y", y),
                    "data", data));
            index++;
        }

        // Start is structural editor metadata. The proposal API accepts only executable nodes so
        // the model cannot accidentally create duplicate Start nodes.
        graphNodes.addFirst(Map.of(
                "id", "start",
                "type", "start",
                "position", Map.of("x", -260, "y", 100),
                "data", Map.of()));
        Set<String> reservedIds = new LinkedHashSet<>(nodeIds);
        reservedIds.add("start");
        preserveNotes(context, graphNodes, reservedIds);
        // Last-line defense: whatever combined the graph above, the emitted proposal must never
        // carry two nodes with the same id (the builder keys canvas nodes by id).
        Set<String> emittedIds = new HashSet<>();
        for (Map<String, Object> node : graphNodes) {
            if (!emittedIds.add(text(node.get("id")))) throw new IllegalArgumentException(
                    "Proposal graph contains a duplicate node id: " + text(node.get("id")));
        }

        List<?> rawEdges = list(request.get("edges"), "edges");
        List<Map<String, Object>> graphEdges = new ArrayList<>();
        Set<String> edgeKeys = new HashSet<>();
        for (int edgeIndex = 0; edgeIndex < rawEdges.size(); edgeIndex++) {
            Map<String, Object> edge = object(rawEdges.get(edgeIndex), "edges[" + edgeIndex + "]");
            String source = requiredText(edge, "source");
            String target = requiredText(edge, "target");
            if (!nodeIds.contains(source) || !nodeIds.contains(target)) {
                throw new IllegalArgumentException("Edge references an unknown node: " + source + " -> " + target);
            }
            if (source.equals(target)) throw new IllegalArgumentException("Self edges are not supported: " + source);
            String sourceHandle = text(edge.get("sourceHandle"));
            String key = source + "\u0000" + target + "\u0000" + sourceHandle;
            if (!edgeKeys.add(key)) continue;
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("id", "ai_edge_" + graphEdges.size());
            normalized.put("source", source);
            normalized.put("target", target);
            if (!sourceHandle.isBlank()) normalized.put("sourceHandle", sourceHandle);
            graphEdges.add(normalized);
        }
        rejectCycle(nodeIds, graphEdges);
        validateProposalReferences(inputSchema, goal, nodeArguments, graphEdges);

        Map<String, Object> graph = Map.of("nodes", graphNodes, "edges", graphEdges);
        Map<String, Object> proposedContext = new LinkedHashMap<>();
        proposedContext.put("name", name);
        proposedContext.put("description", description);
        proposedContext.put("goal", goal);
        proposedContext.put("inputSchema", inputSchema);
        proposedContext.put("graph", graph);
        List<Map<String, Object>> issues = diagnose(proposedContext, catalog);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "flow_proposal");
        result.put("baseWorkflowId", context.get("workflowId"));
        result.put("baseRevision", context.get("revision"));
        result.put("baseSnapshotId", context.get("snapshotId"));
        result.put("name", name);
        result.put("description", description);
        result.put("goal", goal);
        result.put("inputSchema", inputSchema);
        result.put("graph", graph);
        result.put("summary", firstNonBlank(text(request.get("summary")), "AI Flow proposal"));
        result.put("diagnostics", issues);
        // Machine-decidable gating hint: a proposal whose own diagnostics carry an error must not
        // be applied as-is (the builder additionally re-validates before saving).
        result.put("applicable",
                issues.stream().noneMatch(issue -> "error".equals(text(issue.get("severity")))));
        return result;
    }

    private static Map<String, Object> diagnosticEnvelope(Map<String, Object> context,
                                                           List<Map<String, Object>> catalog) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "flow_diagnostics");
        result.put("workflowId", context.get("workflowId"));
        result.put("snapshotId", context.get("snapshotId"));
        result.put("issues", diagnose(context, catalog));
        result.put("graph", context.getOrDefault("graph", Map.of()));
        return result;
    }

    private static List<Map<String, Object>> diagnose(Map<String, Object> context,
                                                       List<Map<String, Object>> catalog) {
        List<Map<String, Object>> issues = new ArrayList<>();
        Object clientIssues = context.get("diagnostics");
        if (clientIssues instanceof Collection<?> collection) {
            for (Object issue : collection) {
                if (issue instanceof Map<?, ?> map) issues.add(stringKeyMap(map));
            }
        }
        Map<String, Object> graph = mapOrEmpty(context.get("graph"));
        List<?> nodes = graph.get("nodes") instanceof List<?> list ? list : List.of();
        List<?> edges = graph.get("edges") instanceof List<?> list ? list : List.of();
        Map<String, Map<String, Object>> tools = new HashMap<>();
        for (Map<String, Object> tool : catalog) tools.put(text(tool.get("name")), tool);
        Set<String> ids = new LinkedHashSet<>();
        Set<String> toolIds = new LinkedHashSet<>();
        int executable = 0;
        for (Object raw : nodes) {
            if (!(raw instanceof Map<?, ?> rawMap)) continue;
            Map<String, Object> node = stringKeyMap(rawMap);
            String id = text(node.get("id"));
            if (id.isBlank() || !ids.add(id)) {
                addIssue(issues, "error", "duplicate_node", "A node id is blank or duplicated", id);
                continue;
            }
            if (!"tool".equals(text(node.get("type")))) continue;
            executable++;
            toolIds.add(id);
            Map<String, Object> data = mapOrEmpty(node.get("data"));
            String toolName = text(data.get("toolName"));
            Map<String, Object> tool = tools.get(toolName);
            if (tool == null) {
                addIssue(issues, "error", "unavailable_tool", "Tool is unavailable: " + toolName, id);
                continue;
            }
            Object argsText = data.get("argsText");
            try {
                Map<String, Object> args = argsText instanceof String string
                        ? JSON.readValue(string, new TypeReference<>() {})
                        : mapOrEmpty(data.get("args"));
                missingToolArguments(id, args, text(tool.get("inputSchema"))).forEach(
                        field -> addIssue(issues, "error", "missing_argument",
                                "Required argument is missing: " + field, id));
            } catch (Exception error) {
                addIssue(issues, "error", "invalid_arguments", "Node arguments are not valid JSON", id);
            }
        }
        if (executable == 0) addIssue(issues, "warning", "empty_flow", "The Flow has no executable nodes", null);

        List<Map<String, Object>> normalizedEdges = new ArrayList<>();
        for (Object raw : edges) {
            if (!(raw instanceof Map<?, ?> rawMap)) continue;
            Map<String, Object> edge = stringKeyMap(rawMap);
            String source = text(edge.get("source"));
            String target = text(edge.get("target"));
            if (!ids.contains(source) || !ids.contains(target)) {
                addIssue(issues, "error", "dangling_edge",
                        "Edge references an unknown node: " + source + " -> " + target, null);
            } else if (toolIds.contains(source) && toolIds.contains(target)) {
                normalizedEdges.add(edge);
            }
        }
        if (hasCycle(toolIds, normalizedEdges)) {
            addIssue(issues, "error", "cycle", "The Flow contains a dependency cycle", null);
        }
        diagnoseReferences(context, nodes, toolIds, issues);
        return deduplicateIssues(issues);
    }

    private static void diagnoseReferences(Map<String, Object> context, List<?> nodes,
                                           Set<String> toolIds, List<Map<String, Object>> issues) {
        Map<String, Object> inputSchema = mapOrEmpty(context.get("inputSchema"));
        Set<String> inputs = mapOrEmpty(inputSchema.get("properties")).keySet();
        List<String> texts = new ArrayList<>();
        texts.add(text(context.get("goal")));
        for (Object raw : nodes) {
            if (!(raw instanceof Map<?, ?> rawMap)) continue;
            Map<String, Object> node = stringKeyMap(rawMap);
            Map<String, Object> data = mapOrEmpty(node.get("data"));
            texts.add(text(data.get("argsText")));
        }
        for (String value : texts) {
            Matcher inputMatcher = INPUT_REFERENCE.matcher(value);
            while (inputMatcher.find()) {
                String root = inputMatcher.group(1).split("\\.", 2)[0];
                if (!inputs.contains(root)) addIssue(issues, "error", "undeclared_input",
                        "Reference uses undeclared Flow input: " + root, null);
            }
            Matcher nodeMatcher = NODE_REFERENCE.matcher(value);
            while (nodeMatcher.find()) {
                String id = nodeMatcher.group(1);
                if (!toolIds.contains(id)) addIssue(issues, "error", "unknown_node_reference",
                        "Reference uses an unknown node: " + id, null);
            }
        }
    }

    private static List<Map<String, Object>> deduplicateIssues(List<Map<String, Object>> issues) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> issue : issues) {
            String key = text(issue.get("code")) + "\u0000" + text(issue.get("nodeId"))
                    + "\u0000" + text(issue.get("message"));
            if (seen.add(key)) result.add(Map.copyOf(issue));
        }
        return List.copyOf(result);
    }

    private static void addIssue(List<Map<String, Object>> issues, String severity, String code,
                                 String message, String nodeId) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("severity", severity);
        issue.put("code", code);
        issue.put("message", message);
        if (nodeId != null && !nodeId.isBlank()) issue.put("nodeId", nodeId);
        issues.add(issue);
    }

    private static void requireToolArguments(String nodeId, Map<String, Object> args, String schemaText) {
        List<String> missing = missingToolArguments(nodeId, args, schemaText);
        if (!missing.isEmpty()) throw new IllegalArgumentException(
                "Node " + nodeId + " is missing required argument(s): " + String.join(", ", missing));
    }

    private static List<String> missingToolArguments(String nodeId, Map<String, Object> args,
                                                     String schemaText) {
        if (schemaText == null || schemaText.isBlank()) return List.of();
        try {
            JsonNode required = JSON.readTree(schemaText).path("required");
            if (!required.isArray()) return List.of();
            List<String> missing = new ArrayList<>();
            for (JsonNode field : required) {
                if (field.isTextual() && !args.containsKey(field.textValue())) missing.add(field.textValue());
            }
            return List.copyOf(missing);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static void rejectCycle(Set<String> ids, List<Map<String, Object>> edges) {
        if (hasCycle(ids, edges)) throw new IllegalArgumentException("The proposed Flow contains a cycle");
    }

    private static void validateProposalReferences(Map<String, Object> inputSchema, String goal,
                                                   Map<String, String> nodeArguments,
                                                   List<Map<String, Object>> edges) {
        Set<String> inputs = mapOrEmpty(inputSchema.get("properties")).keySet();
        validateInputReferences(goal, inputs, "Flow goal");
        for (Map.Entry<String, String> entry : nodeArguments.entrySet()) {
            String target = entry.getKey();
            String args = entry.getValue();
            validateInputReferences(args, inputs, "Node " + target);
            Matcher matcher = NODE_REFERENCE.matcher(args);
            while (matcher.find()) {
                String source = matcher.group(1);
                if (!nodeArguments.containsKey(source)) {
                    throw new IllegalArgumentException(
                            "Node " + target + " references unknown node " + source);
                }
                if (!hasDependencyPath(source, target, edges)) {
                    throw new IllegalArgumentException("Node " + target + " references " + source
                            + " but no dependency path connects them");
                }
            }
        }
    }

    private static void validateInputReferences(String value, Set<String> inputs, String subject) {
        Matcher matcher = INPUT_REFERENCE.matcher(value == null ? "" : value);
        while (matcher.find()) {
            String root = matcher.group(1).split("\\.", 2)[0];
            if (!inputs.contains(root)) {
                throw new IllegalArgumentException(
                        subject + " references undeclared Flow input " + root);
            }
        }
    }

    private static boolean hasDependencyPath(String source, String target,
                                             List<Map<String, Object>> edges) {
        if (source.equals(target)) return false;
        Map<String, List<String>> outgoing = new HashMap<>();
        for (Map<String, Object> edge : edges) {
            outgoing.computeIfAbsent(text(edge.get("source")), ignored -> new ArrayList<>())
                    .add(text(edge.get("target")));
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        ready.add(source);
        while (!ready.isEmpty()) {
            String current = ready.removeFirst();
            if (!visited.add(current)) continue;
            for (String next : outgoing.getOrDefault(current, List.of())) {
                if (target.equals(next)) return true;
                ready.add(next);
            }
        }
        return false;
    }

    private static boolean hasCycle(Set<String> ids, List<Map<String, Object>> edges) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        for (String id : ids) indegree.put(id, 0);
        for (Map<String, Object> edge : edges) {
            String source = text(edge.get("source"));
            String target = text(edge.get("target"));
            if (!ids.contains(source) || !ids.contains(target)) continue;
            outgoing.computeIfAbsent(source, ignored -> new ArrayList<>()).add(target);
            indegree.put(target, indegree.get(target) + 1);
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        indegree.forEach((id, count) -> { if (count == 0) ready.add(id); });
        int visited = 0;
        while (!ready.isEmpty()) {
            String id = ready.removeFirst();
            visited++;
            for (String target : outgoing.getOrDefault(id, List.of())) {
                int next = indegree.computeIfPresent(target, (ignored, count) -> count - 1);
                if (next == 0) ready.add(target);
            }
        }
        return visited != ids.size();
    }

    /**
     * Carries the canvas' sticky notes into the proposal. A note id that is blank cannot collide
     * with anything (dropped); a note id duplicating another note, a model node, or the reserved
     * structural Start id would emit a graph the builder cannot mount — the proposal is rejected
     * instead, and the live canvas' own diagnostics explain the corruption.
     */
    private static void preserveNotes(Map<String, Object> context, List<Map<String, Object>> nodes,
                                      Set<String> reservedIds) {
        Map<String, Object> graph = mapOrEmpty(context.get("graph"));
        Object rawNodes = graph.get("nodes");
        if (!(rawNodes instanceof List<?> list)) return;
        int count = 0;
        for (Object raw : list) {
            if (count >= MAX_NOTES || !(raw instanceof Map<?, ?> map)) continue;
            Map<String, Object> node = stringKeyMap(map);
            if (!"note".equals(text(node.get("type")))) continue;
            String id = text(node.get("id"));
            if (id.isBlank()) continue;
            if (!reservedIds.add(id)) throw new IllegalArgumentException(
                    "Cannot preserve canvas note with a duplicate or conflicting id: " + id);
            nodes.add(node);
            count++;
        }
    }

    private static List<Map<String, Object>> authoringCatalog(
            List<AiToolRegistry.ToolDescriptor> descriptors) {
        if (descriptors == null) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (AiToolRegistry.ToolDescriptor descriptor : descriptors) {
            if (descriptor == null || "workflow".equals(descriptor.pluginId())
                    || descriptor.name() == null || descriptor.name().isBlank()
                    || !names.add(descriptor.name())) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", descriptor.name());
            item.put("description", firstNonBlank(
                    descriptor.localizedDescription(), descriptor.description(), descriptor.name()));
            item.put("inputSchema", descriptor.inputSchema());
            item.put("outputSchema", descriptor.outputSchema());
            item.put("flowNode", descriptor.flowNode());
            result.add(item);
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> sanitizeContext(Map<String, Object> raw) {
        Map<String, Object> source = raw == null ? Map.of() : raw;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workflowId", blankToNull(text(source.get("workflowId"))));
        result.put("revision", source.get("revision") instanceof Number n ? n.intValue() : null);
        result.put("serverRevision", source.get("serverRevision") instanceof Number n ? n.intValue() : null);
        result.put("snapshotId", blankToNull(text(source.get("snapshotId"))));
        result.put("dirty", Boolean.TRUE.equals(source.get("dirty")));
        result.put("name", text(source.get("name")));
        result.put("description", text(source.get("description")));
        result.put("goal", text(source.get("goal")));
        result.put("inputSchema", mapOrEmpty(source.get("inputSchema")));
        result.put("graph", sanitizeGraph(source.get("graph")));
        Object diagnostics = source.get("diagnostics");
        result.put("diagnostics", diagnostics instanceof List<?> list ? List.copyOf(list) : List.of());
        return result;
    }

    private static Map<String, Object> sanitizeGraph(Object raw) {
        Map<String, Object> graph = mapOrEmpty(raw);
        List<?> nodes = graph.get("nodes") instanceof List<?> list ? list : List.of();
        List<?> edges = graph.get("edges") instanceof List<?> list ? list : List.of();
        if (nodes.size() > 512 || edges.size() > 1024) {
            throw new IllegalArgumentException("Flow authoring context exceeds graph limits");
        }
        return Map.of("nodes", List.copyOf(nodes), "edges", List.copyOf(edges));
    }

    private static String requiredText(Map<String, Object> map, String field) {
        String value = text(map.get(field)).trim();
        if (value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private static Map<String, Object> object(Object value, String label) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException(label + " must be an object");
        return stringKeyMap(map);
    }

    private static List<?> list(Object value, String label) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException(label + " must be an array");
        return list;
    }

    private static Map<String, Object> mapOrEmpty(Object value) {
        return value instanceof Map<?, ?> map ? stringKeyMap(map) : Map.of();
    }

    private static Map<String, Object> stringKeyMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number number && Double.isFinite(number.doubleValue())
                ? number.doubleValue() : fallback;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private static Object blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalArgumentException("Could not serialize Flow authoring data", error);
        }
    }
}
