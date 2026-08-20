package fan.summer.fengyu.ai.workflow;

import fan.summer.fengyu.ai.agent.AgentPlan;
import fan.summer.fengyu.ai.agent.AgentStep;
import fan.summer.fengyu.database.entity.ai.WorkflowEntity;
import fan.summer.fengyu.database.entity.ai.WorkflowRevisionEntity;
import fan.summer.fengyu.database.repository.ai.WorkflowRepository;
import fan.summer.fengyu.database.repository.ai.WorkflowRevisionRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowServiceTest {
    private WorkflowRepository repository;
    private WorkflowRevisionRepository revisionRepository;
    private WorkflowService service;

    @BeforeEach
    void setUp() {
        repository = mock(WorkflowRepository.class);
        revisionRepository = mock(WorkflowRevisionRepository.class);
        SecurityContext security = mock(SecurityContext.class);
        when(security.currentUserId()).thenReturn(1L);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new WorkflowService(repository, revisionRepository, security);
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
    void savingRejectsRetryPoliciesOutsideTheBoundedRange() {
        AgentStep step = new AgentStep(0, "echo", Map.of(), "", false,
                List.of(), null, List.of(), new AgentStep.RetryPolicy(6, 0));
        var draft = new WorkflowService.WorkflowDraft("Retry", "", Map.of(),
                new AgentPlan("", List.of(step), ""), null, null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create(draft));

        assertTrue(error.getMessage().contains("between 1 and 5"));
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

    @Test
    void editingPublishedWorkflowKeepsThePublishedSnapshotActive() throws Exception {
        WorkflowEntity stored = entity(true, new AgentPlan("Old", List.of(), ""));
        stored.setRevision(7);
        when(repository.findByIdAndUserId("flow-1", 1L)).thenReturn(Optional.of(stored));
        var draft = new WorkflowService.WorkflowDraft("Updated", "", Map.of(),
                new AgentPlan("New", List.of(), ""), null, null, 7);

        WorkflowDefinition saved = service.update("flow-1", draft);

        assertEquals(8, saved.revision());
        assertEquals(true, saved.published());
        assertEquals(7, saved.publishedRevision());
        assertEquals(true, saved.hasUnpublishedChanges());
        assertEquals("Updated", saved.name());
        verify(revisionRepository).save(any(WorkflowRevisionEntity.class));
    }

    @Test
    void staleEditorCannotOverwriteANewerWorkflowRevision() throws Exception {
        WorkflowEntity stored = entity(false, new AgentPlan("Current", List.of(), ""));
        stored.setRevision(5);
        when(repository.findByIdAndUserId("flow-1", 1L)).thenReturn(Optional.of(stored));
        var stale = new WorkflowService.WorkflowDraft("Stale", "", Map.of(),
                new AgentPlan("Stale", List.of(), ""), null, null, 4);

        WorkflowRevisionConflictException error = assertThrows(
                WorkflowRevisionConflictException.class,
                () -> service.update("flow-1", stale));

        assertEquals(4, error.expectedRevision());
        assertEquals(5, error.actualRevision());
        verify(repository, never()).save(any());
    }

    @Test
    void staleEditorCannotPublishANewerWorkflowRevision() throws Exception {
        WorkflowEntity stored = entity(false, new AgentPlan("Current", List.of(), ""));
        stored.setRevision(9);
        when(repository.findByIdAndUserId("flow-1", 1L)).thenReturn(Optional.of(stored));

        assertThrows(WorkflowRevisionConflictException.class,
                () -> service.setPublished("flow-1", true, 8));

        assertEquals(false, stored.isPublished());
        assertEquals(9, stored.getRevision());
        verify(repository, never()).save(any());
    }

    @Test
    void publishingCreatesAnImmutableSnapshotAndAdvancesTheActiveRevision() throws Exception {
        WorkflowEntity stored = entity(false, new AgentPlan("Draft", List.of(), ""));
        stored.setRevision(4);
        when(repository.findByIdAndUserId("flow-1", 1L)).thenReturn(Optional.of(stored));

        WorkflowDefinition saved = service.setPublished("flow-1", true, 4);

        assertEquals(true, saved.published());
        assertEquals(5, saved.revision());
        assertEquals(5, saved.publishedRevision());
        assertEquals(false, saved.hasUnpublishedChanges());
        verify(revisionRepository).save(org.mockito.ArgumentMatchers.argThat(snapshot ->
                snapshot.getRevision() == 5 && snapshot.getPlanJson().contains("Draft")));
    }

    @Test
    void aiCompilationUsesPublishedSnapshotWhileManualRunUsesNewerDraft() throws Exception {
        WorkflowEntity stored = entity(true, new AgentPlan("Draft goal", List.of(), ""));
        stored.setRevision(6);
        stored.setPublishedRevision(5);
        WorkflowRevisionEntity published = snapshot(5, new AgentPlan("Published goal", List.of(), ""));
        when(repository.findByIdAndUserId("flow-1", 1L)).thenReturn(Optional.of(stored));
        when(revisionRepository.findByWorkflowIdAndUserIdAndRevision("flow-1", 1L, 5))
                .thenReturn(Optional.of(published));

        assertEquals("Published goal", service.compile("flow-1", Map.of(), true).goal());
        assertEquals("Draft goal", service.compile("flow-1", Map.of(), false).goal());
    }

    @Test
    void compilingWorkflowPreservesBranchConditionsAndRetryPolicy() throws Exception {
        AgentStep conditional = new AgentStep(0, "echo", Map.of(), "", false,
                List.of(), null, List.of(new AgentStep.RunCondition(2, "true")),
                new AgentStep.RetryPolicy(3, 500));
        WorkflowEntity stored = entity(false, new AgentPlan("Branch", List.of(conditional), ""));
        when(repository.findByIdAndUserId("flow-1", 1L)).thenReturn(Optional.of(stored));

        AgentPlan compiled = service.compile("flow-1", Map.of(), false);

        assertEquals(List.of(new AgentStep.RunCondition(2, "true")),
                compiled.steps().getFirst().runWhen());
        assertEquals(new AgentStep.RetryPolicy(3, 500),
                compiled.steps().getFirst().retryPolicy());
    }

    @Test
    void restoringPublishedRevisionCreatesANewDraftWithoutChangingActiveSnapshot() throws Exception {
        WorkflowEntity stored = entity(true, new AgentPlan("Current draft", List.of(), ""));
        stored.setRevision(8);
        stored.setPublishedRevision(7);
        WorkflowRevisionEntity old = snapshot(3, new AgentPlan("Restored goal", List.of(), ""));
        when(repository.findByIdAndUserId("flow-1", 1L)).thenReturn(Optional.of(stored));
        when(revisionRepository.findByWorkflowIdAndUserIdAndRevision("flow-1", 1L, 3))
                .thenReturn(Optional.of(old));

        WorkflowDefinition restored = service.restore("flow-1", 3, 8);

        assertEquals("Restored goal", restored.plan().goal());
        assertEquals(9, restored.revision());
        assertEquals(7, restored.publishedRevision());
        assertEquals(true, restored.hasUnpublishedChanges());
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

    private WorkflowRevisionEntity snapshot(int revision, AgentPlan plan) throws Exception {
        WorkflowRevisionEntity snapshot = new WorkflowRevisionEntity();
        snapshot.setId("flow-1:" + revision);
        snapshot.setWorkflowId("flow-1");
        snapshot.setUserId(1L);
        snapshot.setRevision(revision);
        snapshot.setName("Snapshot");
        snapshot.setDescription("");
        snapshot.setInputSchemaJson("{\"type\":\"object\",\"properties\":{}}");
        snapshot.setPlanJson(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(plan));
        snapshot.setLayoutJson("{}");
        snapshot.setPublishedAt(LocalDateTime.now());
        return snapshot;
    }
}
