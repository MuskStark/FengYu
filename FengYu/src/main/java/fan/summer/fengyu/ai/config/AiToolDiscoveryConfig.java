package fan.summer.fengyu.ai.config;

import fan.summer.fengyu.ai.FengYuTool;
import fan.summer.fengyu.plugin.market.OfficialPluginSeeder;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.runtime.PluginProcessManager;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import fan.summer.fengyu.ai.workflow.WorkflowExecutionService;
import fan.summer.fengyu.ai.workflow.WorkflowService;
import fan.summer.fengyu.ai.mcp.McpRuntimeManager;

import java.util.List;

/**
 * Aggregates every Spring AI {@code @Tool}-annotated bean into a single {@link ToolCallback}[]
 * (spec §3.2.3 — "FengYu provides a {@code @Configuration} to aggregate the available tools").
 *
 * <p><b>Why this exists:</b> FengYu drives Spring AI through manual {@code @Bean} configuration
 * (the non-starter artifacts {@code spring-ai-openai/-anthropic/-ollama} + {@code spring-ai-client-chat}),
 * none of which ships a {@code spring.boot.autoconfigure.AutoConfiguration.imports} entry. Spring AI's
 * starter auto-config is what normally turns {@code @Tool}-annotated beans into {@link ToolCallback}
 * beans — without it, an {@code @Autowired Collection<ToolCallback>} finds zero beans. This class is
 * the explicit aggregation point: it creates the live {@link AiToolRegistry} and retains a
 * startup callback-array bean for chat-backend compatibility.
 *
 * <p><b>Scalable discovery via the {@link FengYuTool} marker:</b> rather than listing each tool bean
 * positionally in this config's signature (which forced a config edit for every new tool), discovery
 * is now driven by the marker interface. Any {@code @Component} that {@code implements FengYuTool}
 * is auto-collected by Spring into the injected {@code List<FengYuTool>}. Adding a new tool is a
 * one-line change on the tool class itself ({@code implements FengYuTool}) and requires <em>no</em>
 * edit here. When a plugin-injection path lands, plugin {@code @Tool} beans that implement the marker
 * are aggregated the same way automatically.
 *
 * <p><b>Single source of truth:</b> The agent runner and controller read live registry snapshots;
 * chat backends retain the startup {@code ToolCallback[]} compatibility bean.
 *
 * <p>No mode/visibility filtering yet (spec §3.2.4 defaults to "no filtering"); when needed, filter
 * the callbacks here before returning.
 */
@Configuration
public class AiToolDiscoveryConfig {

    /**
     * The live tool registry, with built-ins captured once and plugin/MCP tools discovered per snapshot.
     *
     * @param tools every {@link FengYuTool} bean in the context (collected by type — add a tool by
     *              having it {@code implements FengYuTool}; no edit to this config needed)
     */
    @Bean
    public AiToolRegistry aiToolRegistry(List<FengYuTool> tools, OfficialPluginSeeder seeder,
            PluginPackageService packages, PluginProcessManager processes,
            ObjectProvider<SyncMcpToolCallbackProvider> mcpProvider,
            McpRuntimeManager mcpRuntime,
            ObjectProvider<WorkflowService> workflowProvider,
            ObjectProvider<WorkflowExecutionService> workflowExecutionProvider,
            ObjectProvider<fan.summer.fengyu.ai.tools.ToolGuardService> guardProvider) {
        seeder.seed();
        return new AiToolRegistry(tools, packages, processes, mcpProvider,
                workflowProvider, workflowExecutionProvider, mcpRuntime,
                guardProvider.getIfAvailable());
    }

    /** Startup snapshot retained for chat-backend compatibility; Agent uses the live registry. */
    @Bean
    public ToolCallback[] aiToolCallbacks(AiToolRegistry registry) {
        return registry.callbacks().toArray(ToolCallback[]::new);
    }
}
