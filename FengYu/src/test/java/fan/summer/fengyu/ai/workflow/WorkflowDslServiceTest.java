package fan.summer.fengyu.ai.workflow;

import fan.summer.fengyu.ai.agent.AgentPlan;
import fan.summer.fengyu.ai.agent.AgentStep;
import fan.summer.fengyu.ai.config.AiToolRegistry;
import fan.summer.fengyu.database.entity.ai.WorkflowEntity;
import fan.summer.fengyu.database.repository.ai.WorkflowRepository;
import fan.summer.fengyu.database.repository.ai.WorkflowRevisionRepository;
import fan.summer.fengyu.security.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowDslServiceTest {
    private WorkflowRepository repository;
    private AiToolRegistry registry;
    private WorkflowDslService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(WorkflowRepository.class);
        WorkflowRevisionRepository revisions = mock(WorkflowRevisionRepository.class);
        SecurityContext security = mock(SecurityContext.class);
        when(security.currentUserId()).thenReturn(1L);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        WorkflowService workflows = new WorkflowService(repository, revisions, security);
        registry = mock(AiToolRegistry.class);
        when(registry.descriptors(anyString())).thenReturn(List.of(
                descriptor("echo"), descriptor("flow_llm")));
        ObjectProvider<AiToolRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        service = new WorkflowDslService(workflows, provider);
    }

    @Test
    void exportEmbedsGraphAndOmitsDerivedPlan() {
        WorkflowEntity entity = entity("flow-1");
        entity.setGraphJson("{\"nodes\":[{\"id\":\"start\",\"type\":\"start\"}],\"edges\":[]}");
        entity.setLayoutJson("{\"0\":{\"x\":10,\"y\":20}}");
        when(repository.findByIdAndUserId("flow-1", 1L)).thenReturn(Optional.of(entity));

        Map<String, Object> document = service.export("flow-1");

        assertEquals(WorkflowDslService.FORMAT, document.get("format"));
        assertEquals(WorkflowDslService.SCHEMA_VERSION, document.get("schemaVersion"));
        assertEquals("Digest", document.get("name"));
        assertEquals("Summarize", document.get("goal"));
        assertNotNull(document.get("graph"));
        assertNotNull(document.get("layout"));
        assertNull(document.get("plan"), "graph documents never ship a derived plan");
        assertTrue(document.get("inputSchema") instanceof Map<?, ?>);
    }

    @Test
    void exportOfPreGraphDefinitionEmbedsThePlan() {
        WorkflowEntity entity = entity("flow-2");
        when(repository.findByIdAndUserId("flow-2", 1L)).thenReturn(Optional.of(entity));

        Map<String, Object> document = service.export("flow-2");

        assertNull(document.get("graph"));
        assertTrue(document.get("plan") instanceof AgentPlan);
    }

    @Test
    void importRejectsForeignDocuments() {
        Map<String, Object> document = planDocument("echo");
        document.put("format", "n8n");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.importDsl(document));
        assertTrue(error.getMessage().contains("fengyu-flow"));
    }

    @Test
    void importRejectsUnsupportedSchemaVersion() {
        Map<String, Object> document = planDocument("echo");
        document.put("schemaVersion", 99);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.importDsl(document));
        assertTrue(error.getMessage().contains("schemaVersion"));
    }

    @Test
    void importOfGraphOnlyDocumentPointsAtTheBuilder() {
        Map<String, Object> document = planDocument("echo");
        document.remove("plan");
        document.put("graph", Map.of("nodes", List.of(), "edges", List.of()));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.importDsl(document));
        assertTrue(error.getMessage().contains("builder"));
    }

    @Test
    void importCreatesTheWorkflowAndReportsCatalogGaps() {
        Map<String, Object> document = planDocument("echo", "ghost_tool", "ghost_tool");

        WorkflowDslService.DslImportResult result = service.importDsl(document);

        assertEquals("Digest", result.workflow().name());
        assertEquals(3, result.workflow().plan().steps().size());
        assertFalse(result.workflow().published());
        assertEquals(List.of("ghost_tool"), result.missingTools(), "deduplicated, catalog-missing only");
    }

    @Test
    void importRunsTheSaveValidation() {
        Map<String, Object> document = planDocument("run_workflow_abc");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.importDsl(document));
        assertTrue(error.getMessage().contains("Nested workflow"));
    }

    @Test
    void importDefaultsMissingOptionalFields() {
        Map<String, Object> document = planDocument("echo");
        document.remove("name");
        document.remove("inputSchema");
        document.remove("description");

        WorkflowDslService.DslImportResult result = service.importDsl(document);

        assertEquals("Imported Flow", result.workflow().name());
        assertEquals("object", result.workflow().inputSchema().get("type"));
    }

    @Test
    void downloadFilenameIsAFilesystemSafeSlug() {
        assertEquals("weekly-digest-v2.fyf", WorkflowDslService.downloadFilename("Weekly Digest  v2!"));
        assertEquals("flow.fyf", WorkflowDslService.downloadFilename("   "));
        assertEquals("flow.fyf", WorkflowDslService.downloadFilename("周报"));
    }

    private static Map<String, Object> planDocument(String... toolNames) {
        Map<String, Object> document = new HashMap<>();
        document.put("format", WorkflowDslService.FORMAT);
        document.put("schemaVersion", WorkflowDslService.SCHEMA_VERSION);
        document.put("name", "Digest");
        document.put("description", "Summarize a topic");
        document.put("inputSchema", Map.of("type", "object", "properties", Map.of()));
        List<Map<String, Object>> steps = new java.util.ArrayList<>();
        for (int index = 0; index < toolNames.length; index++) {
            steps.add(Map.of("index", index, "toolName", toolNames[index],
                    "description", "Step " + index, "args", Map.of()));
        }
        document.put("plan", Map.of("goal", "Summarize", "reasoning", "", "steps", steps));
        return document;
    }

    private static WorkflowEntity entity(String id) {
        WorkflowEntity entity = new WorkflowEntity();
        entity.setId(id);
        entity.setUserId(1L);
        entity.setName("Digest");
        entity.setDescription("Summarize a topic");
        entity.setInputSchemaJson("{\"type\":\"object\",\"properties\":{}}");
        entity.setPlanJson("{\"goal\":\"Summarize\",\"reasoning\":\"\",\"steps\":["
                + "{\"index\":0,\"toolName\":\"echo\",\"args\":{},\"description\":\"Echo\","
                + "\"requiresApproval\":false}]}");
        entity.setLayoutJson("{}");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }

    private static AiToolRegistry.ToolDescriptor descriptor(String name) {
        return new AiToolRegistry.ToolDescriptor("builtin:" + name, null, name, name,
                "{\"type\":\"object\",\"properties\":{}}", null, "1", null, null, true);
    }
}
