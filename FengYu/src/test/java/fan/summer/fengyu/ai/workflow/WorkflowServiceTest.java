package fan.summer.fengyu.ai.workflow;

import fan.summer.fengyu.ai.agent.AgentPlan;
import fan.summer.fengyu.ai.agent.AgentStep;
import fan.summer.fengyu.database.entity.ai.WorkflowEntity;
import fan.summer.fengyu.database.repository.ai.WorkflowRepository;
import fan.summer.fengyu.security.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowServiceTest {
    private WorkflowRepository repository;
    private WorkflowService service;

    @BeforeEach
    void setUp() {
        repository = mock(WorkflowRepository.class);
        SecurityContext security = mock(SecurityContext.class);
        when(security.currentUserId()).thenReturn(1L);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new WorkflowService(repository, security);
    }

    @Test
    void compileBindsInputsWithoutLosingJsonTypes() throws Exception {
        WorkflowEntity entity = entity(true, new AgentPlan(
                "Process {{inputs.customer}}",
                List.of(new AgentStep(0, "echo", Map.of(
                        "count", "{{inputs.count}}",
                        "label", "customer={{inputs.customer}}",
                        "settings", "{{inputs.settings}}"), "Echo", false)),
                "saved workflow"));
        entity.setInputSchemaJson("""
                {"type":"object","properties":{"customer":{"type":"string"},
                "count":{"type":"integer"},"settings":{"type":"object"}},
                "required":["customer","count"]}
                """);
        when(repository.findByIdAndUserId("flow-1", 1L)).thenReturn(Optional.of(entity));

        AgentPlan compiled = service.compile("flow-1", Map.of(
                "customer", "Ada", "count", 3, "settings", Map.of("dryRun", true)), false);

        assertEquals("Process Ada", compiled.goal());
        assertEquals(3, compiled.steps().getFirst().args().get("count"));
        assertEquals("customer=Ada", compiled.steps().getFirst().args().get("label"));
        assertEquals(Map.of("dryRun", true), compiled.steps().getFirst().args().get("settings"));
    }

    @Test
    void aiCompileRequiresPublicationAndRequiredInputs() throws Exception {
        WorkflowEntity entity = entity(false, new AgentPlan("Test", List.of(), ""));
        entity.setInputSchemaJson("{\"type\":\"object\",\"required\":[\"query\"]}");
        when(repository.findByIdAndUserId("flow-1", 1L)).thenReturn(Optional.of(entity));

        assertThrows(IllegalStateException.class,
                () -> service.compile("flow-1", Map.of("query", "hello"), true));
        entity.setPublished(true);
        assertThrows(IllegalArgumentException.class,
                () -> service.compile("flow-1", Map.of(), true));
    }

    @Test
    void definitionsCannotContainWorkflowTools() {
        var draft = new WorkflowService.WorkflowDraft("Recursive", "", Map.of(),
                new AgentPlan("", List.of(new AgentStep(0, "run_workflow_other",
                        Map.of(), "", false)), ""), null, null);
        assertThrows(IllegalArgumentException.class, () -> service.create(draft));
    }

    @Test
    void savingRejectsUndeclaredInputReferencesInsteadOfFailingAtRunTime() {
        var draft = new WorkflowService.WorkflowDraft("Broken", "",
                Map.of("type", "object", "properties", Map.of("known", Map.of("type", "string"))),
                new AgentPlan("Use {{inputs.missing}}", List.of(new AgentStep(0, "echo",
                        Map.of("text", "{{inputs.alsoMissing}}", "ok", "{{inputs.known}}"),
                        "Echo", false)), ""), null, null);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create(draft));
        assertTrue(error.getMessage().contains("missing"));
        assertTrue(error.getMessage().contains("alsoMissing"));
    }

    @Test
    void savingRejectsPlansBeyondTheStepCap() {
        List<AgentStep> steps = new java.util.ArrayList<>();
        for (int i = 0; i < 65; i++) {
            steps.add(new AgentStep(i, "echo", Map.of(), "", false));
        }
        var draft = new WorkflowService.WorkflowDraft("Huge", "", Map.of(),
                new AgentPlan("", List.copyOf(steps), ""), null, null);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create(draft));
        assertTrue(error.getMessage().contains("64 steps"));
    }

    @Test
    void savingRejectsLayoutPositionsOutsideTheStepList() {
        var plan = new AgentPlan("One", List.of(new AgentStep(0, "echo", Map.of(), "", false)), "");
        var draft = new WorkflowService.WorkflowDraft("Layout", "", Map.of(), plan,
                Map.of("0", new WorkflowDefinition.NodeLayout(12, 34),
                        "7", new WorkflowDefinition.NodeLayout(-1, 0)), null);
        assertThrows(IllegalArgumentException.class, () -> service.create(draft));
    }

    @Test
    void layoutRoundTripsThroughCreateAndRead() throws Exception {
        var plan = new AgentPlan("One", List.of(new AgentStep(0, "echo", Map.of(), "", false)), "");
        var draft = new WorkflowService.WorkflowDraft("Layout", "", Map.of(), plan,
                Map.of("0", new WorkflowDefinition.NodeLayout(120.5, -40)),
                Map.of("nodes", List.of(), "edges", List.of()));
        WorkflowDefinition saved = service.create(draft);
        assertEquals(Map.of("0", new WorkflowDefinition.NodeLayout(120.5, -40)), saved.layout());
        assertEquals(Map.of("nodes", List.of(), "edges", List.of()), saved.graph());

        WorkflowEntity stored = entity(true, plan);
        stored.setLayoutJson("{\"0\":{\"x\":10,\"y\":20}}");
        when(repository.findByIdAndUserId("flow-1", 1L)).thenReturn(Optional.of(stored));
        assertEquals(new WorkflowDefinition.NodeLayout(10, 20), service.get("flow-1").layout().get("0"));
    }

    @Test
    void corruptedOrNullLayoutFallsBackToEmptyWithoutBlockingReads() throws Exception {
        WorkflowEntity stored = entity(true, new AgentPlan("T", List.of(), ""));
        stored.setLayoutJson("{not json");
        when(repository.findByIdAndUserId("flow-1", 1L)).thenReturn(Optional.of(stored));
        assertEquals(Map.of(), service.get("flow-1").layout());
        stored.setLayoutJson(null);
        assertEquals(Map.of(), service.get("flow-1").layout());
    }

    @Test
    void corruptedOrNullGraphFallsBackToEmptyWithoutBlockingReads() throws Exception {
        WorkflowEntity stored = entity(true, new AgentPlan("T", List.of(), ""));
        stored.setGraphJson("{not json");
        when(repository.findByIdAndUserId("flow-1", 1L)).thenReturn(Optional.of(stored));
        assertEquals(Map.of(), service.get("flow-1").graph());
        stored.setGraphJson(null);
        assertEquals(Map.of(), service.get("flow-1").graph());
    }

    @Test
    void graphWithStickyNotesRoundTripsThroughCreate() {
        var plan = new AgentPlan("One", List.of(new AgentStep(0, "echo", Map.of(), "", false)), "");
        var graph = Map.<String, Object>of(
                "nodes", List.of(
                        Map.of("id", "node_1", "type", "tool", "position", Map.of("x", 10, "y", 20)),
                        Map.of("id", "note_1", "type", "note", "data",
                                Map.of("content", "check recipients", "color", "yellow"))),
                "edges", List.of(Map.of("id", "e1", "source", "node_1", "target", "node_1")));
        var draft = new WorkflowService.WorkflowDraft("Notes", "", Map.of(), plan, null, graph);
        WorkflowDefinition saved = service.create(draft);
        assertEquals(2, ((List<?>) saved.graph().get("nodes")).size());
    }

    @Test
    void savingRejectsMalformedGraphShapes() {
        var plan = new AgentPlan("One", List.of(new AgentStep(0, "echo", Map.of(), "", false)), "");
        var draft = new WorkflowService.WorkflowDraft("Bad", "", Map.of(), plan, null,
                Map.of("nodes", "not-a-list"));
        assertThrows(IllegalArgumentException.class, () -> service.create(draft));
    }

    private WorkflowEntity entity(boolean published, AgentPlan plan) throws Exception {
        WorkflowEntity entity = new WorkflowEntity();
        entity.setId("flow-1");
        entity.setUserId(1L);
        entity.setName("Example");
        entity.setDescription("Example workflow");
        entity.setInputSchemaJson("{\"type\":\"object\",\"properties\":{}}");
        entity.setPlanJson(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(plan));
        entity.setPublished(published);
        entity.setRevision(1);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }
}
