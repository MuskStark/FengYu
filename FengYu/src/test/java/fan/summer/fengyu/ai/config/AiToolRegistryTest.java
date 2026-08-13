package fan.summer.fengyu.ai.config;

import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.runtime.PluginProcessManager;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService;
import fan.summer.fengyu.ai.ChatFileContext;
import org.junit.jupiter.api.AfterEach;
import fan.summer.fengyu.ai.tools.AuditedToolCallback;
import fan.summer.fengyu.ai.tools.ToolEffect;
import fan.summer.fengyu.ai.tools.ToolEffectProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.ObjectProvider;
import fan.summer.fengyu.ai.agent.AgentPlan;
import fan.summer.fengyu.ai.workflow.WorkflowDefinition;
import fan.summer.fengyu.ai.workflow.WorkflowExecutionService;
import fan.summer.fengyu.ai.workflow.WorkflowService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.*;

class AiToolRegistryTest {

    @TempDir Path temp;

    @AfterEach
    void clearFileContext() {
        ChatFileContext.clear();
    }

    @Test
    void pluginCatalogFollowsEnableAndUninstallWithoutRecreatingRegistry() throws Exception {
        Path plugin = temp.resolve("com.example.live");
        Files.createDirectories(plugin);
        Files.writeString(plugin.resolve("manifest.json"), """
            {"schemaVersion":2,"id":"com.example.live","name":"Live","description":"Live tools",
             "version":"1.0.0","author":"Example","icon":"toolbox","category":"dev",
             "ui":{"entry":"ui/index.html"},
             "rpc":{"methods":{"echo":{
               "inputSchema":{"type":"object","properties":{}},
               "outputSchema":{"type":"object","properties":{"text":{"type":"string"}}}
             }}},
             "aiTools":[{"name":"live_echo","description":"Echo","method":"echo","effect":"read"}]}
            """);
        JsonMapper.builder().findAndAddModules().build()
                .readValue(plugin.resolve("manifest.json").toFile(),
                        fan.summer.fengyu.plugin.market.PluginManifest.class);

        PluginPackageService packages = new PluginPackageService(temp.toString());
        @SuppressWarnings("unchecked")
        ObjectProvider<SyncMcpToolCallbackProvider> mcp = mock(ObjectProvider.class);
        AiToolRegistry registry = new AiToolRegistry(List.of(), packages,
                mock(PluginProcessManager.class), mcp);

        var descriptor = registry.descriptors(null).getFirst();
        assertEquals("com.example.live:live_echo", descriptor.id());
        assertTrue(descriptor.outputSchema().contains("text"));
        // v2 declares effect explicitly ("read"); the old null→EXTERNAL fallback no longer applies.
        assertEquals(ToolEffect.READ,
                ((AuditedToolCallback) registry.callbacks().getFirst()).effect());

        packages.setEnabled("com.example.live", false);
        assertTrue(registry.descriptors(null).isEmpty());
        packages.setEnabled("com.example.live", true);
        assertEquals(1, registry.callbacks().size());
        packages.uninstall("com.example.live");
        assertTrue(registry.callbacks().isEmpty());
    }

    @Test
    void builtinCanDeclarePerCallbackEffects() {
        PluginPackageService packages = new PluginPackageService(temp.toString());
        @SuppressWarnings("unchecked")
        ObjectProvider<SyncMcpToolCallbackProvider> mcp = mock(ObjectProvider.class);
        AiToolRegistry registry = new AiToolRegistry(List.of(new MixedEffectTool()), packages,
                mock(PluginProcessManager.class), mcp);

        Map<String, ToolEffect> effects = registry.callbacks().stream().collect(
                java.util.stream.Collectors.toMap(
                        callback -> callback.getToolDefinition().name(),
                        callback -> ((AuditedToolCallback) callback).effect()));
        assertEquals(ToolEffect.READ, effects.get("inspect_test"));
        assertEquals(ToolEffect.EXTERNAL, effects.get("mutate_test"));
    }

    @Test
    void publishedWorkflowIsDiscoveredAndInvokesSharedExecutionService() {
        PluginPackageService packages = new PluginPackageService(temp.toString());
        @SuppressWarnings("unchecked")
        ObjectProvider<SyncMcpToolCallbackProvider> mcp = mock(ObjectProvider.class);
        WorkflowService workflows = mock(WorkflowService.class);
        WorkflowExecutionService execution = mock(WorkflowExecutionService.class);
        WorkflowDefinition workflow = new WorkflowDefinition(
                "flow-1", "Daily report", "Build the daily report",
                Map.of("type", "object", "properties", Map.of("date", Map.of("type", "string"))),
                new AgentPlan("report", List.of(), ""), true, 3,
                LocalDateTime.now(), LocalDateTime.now());
        when(workflows.published()).thenReturn(List.of(workflow));
        when(workflows.inputSchemaJson(workflow)).thenReturn(
                "{\"type\":\"object\",\"properties\":{\"date\":{\"type\":\"string\"}}}");
        when(execution.executeForAi("flow-1", Map.of("date", "2026-08-13")))
                .thenReturn("report ready");
        @SuppressWarnings("unchecked")
        ObjectProvider<WorkflowService> workflowProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<WorkflowExecutionService> executionProvider = mock(ObjectProvider.class);
        when(workflowProvider.getIfAvailable()).thenReturn(workflows);
        when(executionProvider.getIfAvailable()).thenReturn(execution);

        AiToolRegistry registry = new AiToolRegistry(List.of(), packages,
                mock(PluginProcessManager.class), mcp, workflowProvider, executionProvider);

        assertEquals("run_workflow_flow_1",
                registry.descriptors(null).getFirst().name());
        assertEquals("report ready",
                registry.callbacks().getFirst().call("{\"date\":\"2026-08-13\"}"));
    }

    @Test
    void writeDirectoryToolUsesStagingGrantWithoutRestartingWorker() throws Exception {
        Path plugin = Files.createDirectories(temp.resolve("fan.summer.excel"));
        Files.writeString(plugin.resolve("manifest.json"), """
            {"schemaVersion":2,"id":"fan.summer.excel","name":"Excel","description":"test",
             "version":"1.0.0","author":"test","icon":"table","category":"dev",
             "ui":{"entry":"ui/index.html"},
             "backend":{"callTimeoutSeconds":60},
             "permissions":["files.read","files.write"],
             "rpc":{"methods":{"execute":{
               "inputSchema":{"type":"object","properties":{"outputDir":{"type":"object","description":"A writable FengYu DirectoryRef"}}}
             }}},
             "aiTools":[{"name":"excel_execute","description":"Execute split","method":"execute","effect":"write"}]}
            """);
        PluginPackageService packages = new PluginPackageService(temp.toString());
        PluginFileGrantService files = new PluginFileGrantService(temp.resolve("runtime").toString());
        // A staging grant (access="write") is already in the turn's context — the worker picks it
        // up directly. No per-call promotion or process restart happens.
        var staging = files.outputDirectory("fan.summer.excel");
        ChatFileContext.set(List.of(new ChatFileContext.ActiveFileRef("fan.summer.excel", staging)));
        PluginProcessManager processes = mock(PluginProcessManager.class);
        when(processes.invoke(eq("fan.summer.excel"), eq("execute"), anyMap(), eq(-1L)))
            .thenAnswer(invocation -> {
                @SuppressWarnings("unchecked") Map<String, Object> params = invocation.getArgument(2);
                @SuppressWarnings("unchecked") Map<String, Object> ref = (Map<String, Object>) params.get("outputDir");
                assertEquals("write", ref.get("access"));
                return Map.of("success", true);
            });
        @SuppressWarnings("unchecked")
        ObjectProvider<SyncMcpToolCallbackProvider> mcp = mock(ObjectProvider.class);
        AiToolRegistry registry = new AiToolRegistry(List.of(), packages, processes, mcp);

        registry.callbacks().getFirst().call("{}");

        // The worker is NOT stopped after the call — staging lives for the whole turn.
        verify(processes, never()).stop("fan.summer.excel");
    }

    static final class MixedEffectTool implements ToolEffectProvider {
        @Tool(name = "inspect_test", description = "inspect")
        public String inspect() { return "ok"; }

        @Tool(name = "mutate_test", description = "mutate")
        public String mutate() { return "ok"; }

        @Override public ToolEffect effectFor(String toolName) {
            return "inspect_test".equals(toolName) ? ToolEffect.READ : ToolEffect.EXTERNAL;
        }
    }
}
