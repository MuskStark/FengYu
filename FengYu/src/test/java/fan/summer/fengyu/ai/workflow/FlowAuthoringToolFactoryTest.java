package fan.summer.fengyu.ai.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.ai.config.AiToolRegistry;
import fan.summer.fengyu.ai.tools.AuditedToolCallback;
import fan.summer.fengyu.ai.tools.ToolEffect;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowAuthoringToolFactoryTest {
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test
    void authoringToolsInspectDiagnoseAndReturnNonMutatingProposal() throws Exception {
        Map<String, Object> context = context(Map.of(
                "nodes", List.of(),
                "edges", List.of()));
        List<ToolCallback> callbacks = FlowAuthoringToolFactory.create(context, List.of(tool()));

        assertEquals(List.of("inspect_current_flow", "diagnose_current_flow", "edit_current_flow"),
                callbacks.stream().map(callback -> callback.getToolDefinition().name()).toList());
        assertTrue(callbacks.stream().allMatch(callback ->
                ((AuditedToolCallback) callback).effect() == ToolEffect.READ));

        Map<String, Object> inspection = read(callback(callbacks, "inspect_current_flow").call("{}"));
        assertEquals("flow_inspection", inspection.get("kind"));
        assertEquals(1, ((List<?>) inspection.get("availableTools")).size());

        Map<String, Object> diagnosis = read(callback(callbacks, "diagnose_current_flow").call("{}"));
        assertEquals("flow_diagnostics", diagnosis.get("kind"));
        assertFalse(((List<?>) diagnosis.get("issues")).isEmpty());

        Map<String, Object> proposal = read(callback(callbacks, "edit_current_flow").call("""
                {
                  "name":"Format payload","description":"","goal":"Format the input JSON",
                  "summary":"Add a formatter",
                  "inputSchema":{"type":"object","properties":{"payload":{"type":"string"}},"required":["payload"]},
                  "nodes":[{"id":"formatter","toolName":"json_format","args":{"json":"{{inputs.payload}}"}}],
                  "edges":[]
                }
                """));
        assertEquals("flow_proposal", proposal.get("kind"));
        assertEquals("snapshot-1", proposal.get("baseSnapshotId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> graph = (Map<String, Object>) proposal.get("graph");
        assertEquals(2, ((List<?>) graph.get("nodes")).size(), "proposal adds one structural Start node");
        assertEquals(List.of(), contextGraphNodes(context), "the request context is never mutated");
    }

    @Test
    void proposalRejectsUnavailableToolsMissingArgumentsAndCycles() throws Exception {
        List<ToolCallback> callbacks = FlowAuthoringToolFactory.create(
                context(Map.of("nodes", List.of(), "edges", List.of())), List.of(tool()));
        ToolCallback edit = callback(callbacks, "edit_current_flow");

        Map<String, Object> unavailable = read(edit.call("""
                {"name":"x","goal":"x","inputSchema":{"type":"object"},
                 "nodes":[{"id":"n1","toolName":"missing","args":{}}],"edges":[]}
                """));
        assertEquals("flow_proposal_error", unavailable.get("kind"));
        assertTrue(String.valueOf(unavailable.get("error")).contains("Unavailable"));

        Map<String, Object> missing = read(edit.call("""
                {"name":"x","goal":"x","inputSchema":{"type":"object"},
                 "nodes":[{"id":"n1","toolName":"json_format","args":{}}],"edges":[]}
                """));
        assertEquals("flow_proposal_error", missing.get("kind"));
        assertTrue(String.valueOf(missing.get("error")).contains("missing required"));

        Map<String, Object> cycle = read(edit.call("""
                {"name":"x","goal":"x","inputSchema":{"type":"object"},
                 "nodes":[
                   {"id":"n1","toolName":"json_format","args":{"json":"a"}},
                   {"id":"n2","toolName":"json_format","args":{"json":"b"}}],
                 "edges":[{"source":"n1","target":"n2"},{"source":"n2","target":"n1"}]}
                """));
        assertEquals("flow_proposal_error", cycle.get("kind"));
        assertTrue(String.valueOf(cycle.get("error")).contains("cycle"));

        Map<String, Object> disconnectedReference = read(edit.call("""
                {"name":"x","goal":"x","inputSchema":{"type":"object","properties":{}},
                 "nodes":[
                   {"id":"n1","toolName":"json_format","args":{"json":"a"}},
                   {"id":"n2","toolName":"json_format","args":{"json":"{{node.n1.result}}"}}],
                 "edges":[]}
                """));
        assertEquals("flow_proposal_error", disconnectedReference.get("kind"));
        assertTrue(String.valueOf(disconnectedReference.get("error")).contains("dependency path"));

        Map<String, Object> undeclaredInput = read(edit.call("""
                {"name":"x","goal":"{{inputs.missing}}","inputSchema":{"type":"object","properties":{}},
                 "nodes":[{"id":"n1","toolName":"json_format","args":{"json":"a"}}],"edges":[]}
                """));
        assertEquals("flow_proposal_error", undeclaredInput.get("kind"));
        assertTrue(String.valueOf(undeclaredInput.get("error")).contains("undeclared"));
    }

    @Test
    void proposalRejectsReservedStartIdAndCollidingOrDuplicateNoteIds() throws Exception {
        ToolCallback edit = callback(FlowAuthoringToolFactory.create(
                context(Map.of("nodes", List.of(), "edges", List.of())), List.of(tool())), "edit_current_flow");

        Map<String, Object> reserved = read(edit.call("""
                {"name":"x","goal":"x","inputSchema":{"type":"object"},
                 "nodes":[{"id":"start","toolName":"json_format","args":{"json":"a"}}],"edges":[]}
                """));
        assertEquals("flow_proposal_error", reserved.get("kind"));
        assertTrue(String.valueOf(reserved.get("error")).contains("reserved"),
                "the structural Start id is reserved: " + reserved.get("error"));

        Map<String, Object> colliding = read(callback(FlowAuthoringToolFactory.create(
                context(Map.of("nodes", List.of(
                        Map.of("id", "n1", "type", "note", "position", Map.of("x", 0, "y", 0), "data", Map.of())),
                        "edges", List.of())), List.of(tool())), "edit_current_flow").call("""
                {"name":"x","goal":"x","inputSchema":{"type":"object"},
                 "nodes":[{"id":"n1","toolName":"json_format","args":{"json":"a"}}],"edges":[]}
                """));
        assertEquals("flow_proposal_error", colliding.get("kind"));
        assertTrue(String.valueOf(colliding.get("error")).contains("conflicting"),
                "a note id may not collide with a model node id: " + colliding.get("error"));

        Map<String, Object> duplicateNotes = read(callback(FlowAuthoringToolFactory.create(
                context(Map.of("nodes", List.of(
                        Map.of("id", "note_1", "type", "note", "position", Map.of("x", 0, "y", 0), "data", Map.of()),
                        Map.of("id", "note_1", "type", "note", "position", Map.of("x", 40, "y", 0), "data", Map.of())),
                        "edges", List.of())), List.of(tool())), "edit_current_flow").call("""
                {"name":"x","goal":"x","inputSchema":{"type":"object"},
                 "nodes":[{"id":"n1","toolName":"json_format","args":{"json":"a"}}],"edges":[]}
                """));
        assertEquals("flow_proposal_error", duplicateNotes.get("kind"));
        assertTrue(String.valueOf(duplicateNotes.get("error")).contains("note"),
                "a canvas with duplicate note ids cannot be preserved: " + duplicateNotes.get("error"));
    }

    @Test
    void validProposalCarriesUniqueNodeIdsAndAnApplicableFlag() throws Exception {
        Map<String, Object> proposal = read(callback(FlowAuthoringToolFactory.create(
                context(Map.of("nodes", List.of(
                        Map.of("id", "note_1", "type", "note", "position", Map.of("x", 0, "y", 0), "data", Map.of())),
                        "edges", List.of())), List.of(tool())), "edit_current_flow").call("""
                {"name":"Format","goal":"Format the input","inputSchema":{"type":"object","properties":{"payload":{"type":"string"}}},
                 "nodes":[{"id":"formatter","toolName":"json_format","args":{"json":"{{inputs.payload}}"}}],"edges":[]}
                """));
        assertEquals("flow_proposal", proposal.get("kind"));
        assertEquals(Boolean.TRUE, proposal.get("applicable"),
                "a proposal without error diagnostics is applicable as-is");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) ((Map<String, Object>) proposal.get("graph")).get("nodes");
        long distinctIds = nodes.stream().map(node -> String.valueOf(node.get("id"))).distinct().count();
        assertEquals(nodes.size(), distinctIds, "emitted node ids are globally unique");
    }

    private AiToolRegistry.ToolDescriptor tool() {
        return new AiToolRegistry.ToolDescriptor(
                "builtin:json_format", null, "json_format", "Format JSON",
                "{\"type\":\"object\",\"properties\":{\"json\":{\"type\":\"string\"}},\"required\":[\"json\"]}",
                "{\"type\":\"object\"}", "r1", null, null, true);
    }

    private Map<String, Object> context(Map<String, Object> graph) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("workflowId", null);
        context.put("revision", null);
        context.put("snapshotId", "snapshot-1");
        context.put("dirty", false);
        context.put("name", "");
        context.put("description", "");
        context.put("goal", "");
        context.put("inputSchema", Map.of("type", "object", "properties", Map.of()));
        context.put("graph", graph);
        context.put("diagnostics", List.of());
        return context;
    }

    @SuppressWarnings("unchecked")
    private List<?> contextGraphNodes(Map<String, Object> context) {
        return (List<?>) ((Map<String, Object>) context.get("graph")).get("nodes");
    }

    private ToolCallback callback(List<ToolCallback> callbacks, String name) {
        return callbacks.stream().filter(callback -> name.equals(callback.getToolDefinition().name()))
                .findFirst().orElseThrow();
    }

    private Map<String, Object> read(String value) throws Exception {
        return json.readValue(value, new TypeReference<>() {});
    }
}
