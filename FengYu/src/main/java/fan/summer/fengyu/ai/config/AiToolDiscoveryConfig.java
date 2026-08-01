package fan.summer.fengyu.ai.config;

import fan.summer.fengyu.ai.FengYuTool;
import fan.summer.fengyu.ai.tools.ApprovalRequiredTool;
import fan.summer.fengyu.ai.tools.ApprovalRequiredToolCallback;
import fan.summer.fengyu.plugin.market.OfficialPluginSeeder;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.runtime.PluginProcessManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;

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
 * the explicit aggregation point: it runs every {@link FengYuTool} bean through
 * {@link ToolCallbacks#from(Object...)} and exposes the flattened callbacks as a single bean.
 *
 * <p><b>Scalable discovery via the {@link FengYuTool} marker:</b> rather than listing each tool bean
 * positionally in this config's signature (which forced a config edit for every new tool), discovery
 * is now driven by the marker interface. Any {@code @Component} that {@code implements FengYuTool}
 * is auto-collected by Spring into the injected {@code List<FengYuTool>}. Adding a new tool is a
 * one-line change on the tool class itself ({@code implements FengYuTool}) and requires <em>no</em>
 * edit here. When a plugin-injection path lands, plugin {@code @Tool} beans that implement the marker
 * are aggregated the same way automatically.
 *
 * <p><b>Single source of truth:</b> Tasks 15 ({@code AgentRunner}) and 16
 * ({@code AgentController} {@code /api/agent/tools}) inject this {@code ToolCallback[]} to resolve
 * tools by name and to list orchestrable tools for the agent UI. The chat backends
 * ({@code SpringAiCloudBackend} via {@code AiBackendInitializer}; {@code OllamaLocalBackend} via its
 * own {@code loadModel}) are handed the same array via {@code setToolCallbacks(List.of(...))} so chat
 * regains tool-calling.
 *
 * <p>No mode/visibility filtering yet (spec §3.2.4 defaults to "no filtering"); when needed, filter
 * the callbacks here before returning.
 */
@Configuration
public class AiToolDiscoveryConfig {

    /**
     * The discovered {@link ToolCallback}s, in Spring bean-resolution order. This is the bean Tasks
     * 15/16 and the chat backends inject to resolve tools by name / offer them to the model.
     *
     * @param tools every {@link FengYuTool} bean in the context (collected by type — add a tool by
     *              having it {@code implements FengYuTool}; no edit to this config needed)
     */
    @Bean
    public ToolCallback[] aiToolCallbacks(List<FengYuTool> tools, OfficialPluginSeeder seeder,
            PluginPackageService packages, PluginProcessManager processes,
            ObjectProvider<SyncMcpToolCallbackProvider> mcpProvider) {
        seeder.seed();
        List<ToolCallback> callbacks = new java.util.ArrayList<>();
        for (FengYuTool toolBean : tools) {
            for (ToolCallback callback : ToolCallbacks.from(toolBean)) {
                callbacks.add(toolBean instanceof ApprovalRequiredTool
                        ? approvalRequired(callback)
                        : callback);
            }
        }
        ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
        for (var manifest : packages.installed()) {
            if (!packages.isEnabled(manifest.id()) || manifest.aiTools() == null) continue;
            for (var tool : manifest.aiTools()) {
                callbacks.add(new ToolCallback() {
                    private final ToolDefinition definition = ToolDefinition.builder()
                        .name(tool.name()).description(tool.description()).inputSchema(tool.inputSchema()).build();
                    @Override public ToolDefinition getToolDefinition() { return definition; }
                    @Override public String call(String input) {
                        try {
                            @SuppressWarnings("unchecked") var params = json.readValue(input, java.util.Map.class);
                            // Transparently inject an active FileRef into a single read-class file
                            // param before dispatch (route B). When the tool has no/multiple/write
                            // file params, this is a no-op and the model fills them from the system
                            // prompt (route A). resolveRefs then rewrites the ref to a real path.
                            var injected = fan.summer.fengyu.ai.AiToolFileInjector.injectFileRefs(
                                params, manifest.id(), tool.inputSchema(),
                                fan.summer.fengyu.ai.ChatFileContext.current());
                            // Honour the manifest-declared per-tool timeout; -1 falls back to the
                            // plugin-wide default. Tools that may run long (e.g. excel_execute) can
                            // declare up to 600s; tools that need longer must switch to job mode.
                            long timeout = tool.timeoutSeconds() == null ? -1 : tool.timeoutSeconds();
                            Object result = processes.invoke(manifest.id(), tool.method(), injected, timeout);
                            return result instanceof String text ? text : json.writeValueAsString(result);
                        } catch (Exception e) {
                            return "{\"success\":false,\"error\":" + quote(json, String.valueOf(e.getMessage())) + "}";
                        }
                    }
                });
            }
        }
        SyncMcpToolCallbackProvider provider = mcpProvider.getIfAvailable();
        if (provider != null) {
            java.util.Collections.addAll(callbacks, provider.getToolCallbacks());
        }
        return callbacks.toArray(ToolCallback[]::new);
    }

    private static String quote(ObjectMapper json, String value) {
        try { return json.writeValueAsString(value); } catch (Exception ignored) { return "\"Plugin tool failed\""; }
    }

    private static ApprovalRequiredToolCallback approvalRequired(ToolCallback delegate) {
        return new ApprovalRequiredToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override public String call(String input) {
                return delegate.call(input);
            }
        };
    }
}
