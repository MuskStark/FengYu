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
                        Map.of(), "", false)), ""));
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
