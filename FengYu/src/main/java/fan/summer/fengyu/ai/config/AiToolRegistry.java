package fan.summer.fengyu.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.ai.AiToolFileInjector;
import fan.summer.fengyu.ai.ChatFileContext;
import fan.summer.fengyu.ai.FengYuTool;
import fan.summer.fengyu.ai.tools.AiToolLocaleContext;
import fan.summer.fengyu.ai.tools.ApprovalRequiredTool;
import fan.summer.fengyu.ai.tools.ApprovalRequiredToolCallback;
import fan.summer.fengyu.ai.tools.AuditedToolCallback;
import fan.summer.fengyu.ai.tools.ToolEffect;
import fan.summer.fengyu.ai.tools.ToolEffectProvider;
import fan.summer.fengyu.ai.workflow.WorkflowExecutionService;
import fan.summer.fengyu.ai.workflow.WorkflowService;
import fan.summer.fengyu.ai.mcp.McpRuntimeManager;
import fan.summer.fengyu.ai.service.AiConfigServiceHeadless;
import fan.summer.fengyu.plugin.market.ManifestI18n;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.runtime.PluginProcessManager;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Live catalog of built-in, installed-plugin, and MCP tools.
 *
 * <p>Built-in callbacks are stable for the application lifetime. Plugin manifests and enabled
 * markers are intentionally re-scanned for every snapshot, matching the package service's
 * filesystem-backed lifecycle: installing, upgrading, enabling, disabling, or uninstalling a
 * plugin therefore changes the next agent run without restarting the host.</p>
 */
public final class AiToolRegistry {

    private final List<ToolCallback> builtins;
    private final PluginPackageService packages;
    private final PluginProcessManager processes;
    private final ObjectProvider<SyncMcpToolCallbackProvider> mcpProvider;
    private final McpRuntimeManager mcpRuntime;
    private final ObjectProvider<WorkflowService> workflowProvider;
    private final ObjectProvider<WorkflowExecutionService> workflowExecutionProvider;
    private final java.util.function.BooleanSupplier computerUseEnabled;
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    /** When the desktop shell provides built-in browser tools, suppress the legacy plugin's tools to avoid name collisions. */
    private static final String DESKTOP_PROPERTY = "fengyu.desktop";
    private static final String BROWSER_PLUGIN_ID = "fan.summer.browser";
    /** Screen-control tool family hidden while the Settings master switch is off. */
    private static final String COMPUTER_TOOL_PREFIX = "computer_";

    private static boolean desktopMode() {
        return Boolean.parseBoolean(System.getProperty(DESKTOP_PROPERTY));
    }

    public AiToolRegistry(List<FengYuTool> tools, PluginPackageService packages,
            PluginProcessManager processes, ObjectProvider<SyncMcpToolCallbackProvider> mcpProvider) {
        this(tools, packages, processes, mcpProvider, null, null, null);
    }

    public AiToolRegistry(List<FengYuTool> tools, PluginPackageService packages,
            PluginProcessManager processes, ObjectProvider<SyncMcpToolCallbackProvider> mcpProvider,
            ObjectProvider<WorkflowService> workflowProvider,
            ObjectProvider<WorkflowExecutionService> workflowExecutionProvider) {
        this(tools, packages, processes, mcpProvider, workflowProvider, workflowExecutionProvider, null);
    }

    public AiToolRegistry(List<FengYuTool> tools, PluginPackageService packages,
            PluginProcessManager processes, ObjectProvider<SyncMcpToolCallbackProvider> mcpProvider,
            ObjectProvider<WorkflowService> workflowProvider,
            ObjectProvider<WorkflowExecutionService> workflowExecutionProvider,
            McpRuntimeManager mcpRuntime) {
        this(tools, packages, processes, mcpProvider, workflowProvider, workflowExecutionProvider,
                mcpRuntime, AiConfigServiceHeadless::isComputerUseEnabled);
    }

    /** Full constructor — the computer-use switch is injectable so tests can pin it off. */
    AiToolRegistry(List<FengYuTool> tools, PluginPackageService packages,
            PluginProcessManager processes, ObjectProvider<SyncMcpToolCallbackProvider> mcpProvider,
            ObjectProvider<WorkflowService> workflowProvider,
            ObjectProvider<WorkflowExecutionService> workflowExecutionProvider,
            McpRuntimeManager mcpRuntime, java.util.function.BooleanSupplier computerUseEnabled) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (FengYuTool toolBean : tools) {
            for (ToolCallback callback : ToolCallbacks.from(toolBean)) {
                if (toolBean instanceof ToolEffectProvider effects) {
                    ToolEffect effect = effects.effectFor(callback.getToolDefinition().name());
                    callbacks.add(audited(callback,
                            effect == null ? ToolEffect.EXTERNAL : effect));
                } else {
                    callbacks.add(toolBean instanceof ApprovalRequiredTool
                            ? approvalRequired(callback) : callback);
                }
            }
        }
        this.builtins = List.copyOf(callbacks);
        this.packages = packages;
        this.processes = processes;
        this.mcpProvider = mcpProvider;
        this.mcpRuntime = mcpRuntime;
        this.workflowProvider = workflowProvider;
        this.workflowExecutionProvider = workflowExecutionProvider;
        this.computerUseEnabled = computerUseEnabled;
    }

    /** True when {@code computer_*} tools may appear in this snapshot (Settings master switch). */
    private boolean computerUseVisible() {
        return computerUseEnabled == null || computerUseEnabled.getAsBoolean();
    }

    private static boolean isComputerTool(String name) {
        return name != null && name.startsWith(COMPUTER_TOOL_PREFIX);
    }

    /** An immutable, internally consistent snapshot for one planning/execution operation. */
    public List<ToolCallback> callbacks() {
        boolean computerUse = computerUseVisible();
        List<ToolCallback> callbacks = new ArrayList<>();
        for (ToolCallback callback : builtins) {
            if (!computerUse && isComputerTool(callback.getToolDefinition().name())) continue;
            callbacks.add(callback);
        }
        for (var manifest : packages.installed()) {
            if (!packages.isEnabled(manifest.id()) || manifest.aiTools() == null) continue;
            if (desktopMode() && BROWSER_PLUGIN_ID.equals(manifest.id())) continue;
            for (var tool : manifest.aiTools()) callbacks.add(pluginCallback(manifest.id(), tool));
        }
        SyncMcpToolCallbackProvider provider = mcpProvider.getIfAvailable();
        if (provider != null) {
            for (ToolCallback callback : provider.getToolCallbacks()) {
                callbacks.add(audited(callback, ToolEffect.EXTERNAL));
            }
        }
        if (mcpRuntime != null) {
            for (ToolCallback callback : mcpRuntime.callbacks()) {
                callbacks.add(audited(callback, ToolEffect.EXTERNAL));
            }
        }
        WorkflowService workflowService = workflowProvider == null ? null : workflowProvider.getIfAvailable();
        WorkflowExecutionService executionService = workflowExecutionProvider == null
                ? null : workflowExecutionProvider.getIfAvailable();
        if (workflowService != null && executionService != null) {
            for (var workflow : workflowService.published()) {
                callbacks.add(workflowCallback(workflow, workflowService, executionService));
            }
        }
        return List.copyOf(callbacks);
    }

    /** UI descriptors include stable ownership and output metadata absent from Spring's definition. */
    public List<ToolDescriptor> descriptors(String locale) {
        boolean computerUse = computerUseVisible();
        List<ToolDescriptor> descriptors = new ArrayList<>();
        for (ToolCallback callback : builtins) {
            var definition = callback.getToolDefinition();
            if (!computerUse && isComputerTool(definition.name())) continue;
            descriptors.add(descriptor("builtin:" + definition.name(), null, definition, null, null));
        }
        for (var manifest : packages.installed()) {
            if (!packages.isEnabled(manifest.id()) || manifest.aiTools() == null) continue;
            if (desktopMode() && BROWSER_PLUGIN_ID.equals(manifest.id())) continue;
            for (var tool : manifest.aiTools()) {
                // T2-04 bullet 3: the input schema is resolved from the referenced rpc method's
                // OBJECT schema (a JsonNode) and serialized ONCE at this boundary — Spring AI's
                // ToolDefinition takes a String. This is serialization of a parsed object, not
                // re-parsing a stored string.
                String inputSchema = schemaToString(manifest.inputSchemaFor(tool.method()));
                String outputSchema = schemaToString(manifest.outputSchemaFor(tool.method()));
                ToolDefinition definition = ToolDefinition.builder()
                        .name(tool.name()).description(tool.description()).inputSchema(inputSchema).build();
                // Localized description is for frontend display only; the LLM still sees the English
                // `description` baked into the ToolDefinition above. Falls back to the English original
                // when the manifest ships no i18n override for this tool.
                String localized = ManifestI18n.aiToolDescription(manifest, tool.name(), locale);
                descriptors.add(descriptor(manifest.id() + ":" + tool.name(), manifest.id(),
                        definition, outputSchema, localized));
            }
        }
        SyncMcpToolCallbackProvider provider = mcpProvider.getIfAvailable();
        if (provider != null) {
            for (ToolCallback callback : provider.getToolCallbacks()) {
                var definition = callback.getToolDefinition();
                descriptors.add(descriptor("mcp:" + definition.name(), "mcp", definition, null, null));
            }
        }
        if (mcpRuntime != null) {
            for (ToolCallback callback : mcpRuntime.callbacks()) {
                var definition = callback.getToolDefinition();
                descriptors.add(descriptor("mcp:" + definition.name(), "mcp", definition, null, null));
            }
        }
        WorkflowService workflowService = workflowProvider == null ? null : workflowProvider.getIfAvailable();
        if (workflowService != null) {
            for (var workflow : workflowService.published()) {
                String toolName = workflowToolName(workflow.id());
                ToolDefinition definition = ToolDefinition.builder()
                        .name(toolName)
                        .description(workflowToolDescription(workflow.name(), workflow.description()))
                        .inputSchema(workflowService.inputSchemaJson(workflow))
                        .build();
                descriptors.add(descriptor("workflow:" + workflow.id(), "workflow", definition,
                        "{\"type\":\"object\"}", null));
            }
        }
        return List.copyOf(descriptors);
    }

    private ToolCallback workflowCallback(fan.summer.fengyu.ai.workflow.WorkflowDefinition workflow,
            WorkflowService workflowService, WorkflowExecutionService executionService) {
        return new AuditedToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder()
                    .name(workflowToolName(workflow.id()))
                    .description(workflowToolDescription(workflow.name(), workflow.description()))
                    .inputSchema(workflowService.inputSchemaJson(workflow))
                    .build();

            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public ToolEffect effect() { return ToolEffect.EXTERNAL; }
            @Override public String call(String input) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = json.readValue(input == null ? "{}" : input, Map.class);
                    return executionService.executeForAi(workflow.id(), args);
                } catch (Exception error) {
                    return "{\"success\":false,\"error\":" + quote(String.valueOf(error.getMessage())) + "}";
                }
            }
        };
    }

    private static String workflowToolName(String id) {
        return "run_workflow_" + id.replace('-', '_');
    }

    private static String workflowToolDescription(String name, String description) {
        String detail = description == null || description.isBlank() ? "" : ": " + description;
        return "Run the published FengYu workflow '" + name + "'" + detail;
    }

    private ToolDescriptor descriptor(String id, String pluginId, ToolDefinition definition,
            String outputSchema, String localizedDescription) {
        String revision = Integer.toUnsignedString(Objects.hash(
                definition.description(), definition.inputSchema(), outputSchema), 36);
        return new ToolDescriptor(id, pluginId, definition.name(), definition.description(),
                definition.inputSchema(), outputSchema, revision, localizedDescription);
    }

    private ToolCallback pluginCallback(String pluginId,
            fan.summer.fengyu.plugin.market.PluginManifest.AiTool tool) {
        // T2-04 bullet 3: resolve the input schema ONCE from the referenced rpc method. The
        // serialized form is reused for both the LLM-facing ToolDefinition and the FileRef injector.
        return new AuditedToolCallback() {
            private final String inputSchema = resolveInputSchema(pluginId, tool);
            private final ToolDefinition definition = ToolDefinition.builder()
                    .name(tool.name()).description(tool.description()).inputSchema(inputSchema).build();

            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public ToolEffect effect() {
                // v2 makes effect mandatory; the manifest validator enforces non-null at install.
                return tool.effect() == null ? ToolEffect.EXTERNAL : ToolEffect.from(tool.effect());
            }

            @Override public String call(String input) {
                try {
                    if (packages.find(pluginId).isEmpty() || !packages.isEnabled(pluginId)) {
                        throw new IllegalStateException("Plugin tool is no longer available: " + pluginId);
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> params = json.readValue(input, Map.class);
                    // A WRITE_DIR parameter is satisfied by the staging grant the turn started with
                    // (access="write"), already in ChatFileContext. No per-call promotion or worker
                    // restart is needed: the staging root entered the sandbox at the first invoke.
                    var injected = AiToolFileInjector.injectFileRefs(
                            params, pluginId, inputSchema, ChatFileContext.current());
                    long timeout = tool.timeoutSeconds() == null ? -1 : tool.timeoutSeconds();
                    Object result = processes.invoke(pluginId, tool.method(), injected, timeout, AiToolLocaleContext.current());
                    return result instanceof String text ? text : json.writeValueAsString(result);
                } catch (Exception error) {
                    return "{\"success\":false,\"error\":" + quote(String.valueOf(error.getMessage())) + "}";
                }
            }
        };
    }

    /**
     * Resolve a tool's input schema from its referenced rpc method, serialized to a String for
     * Spring AI / the FileRef injector. Falls back to an empty object schema when the manifest has
     * been removed or the method is missing (a stale callback after an uninstall); the call then
     * surfaces a clean "tool no longer available" error rather than an NPE.
     */
    private String resolveInputSchema(String pluginId,
            fan.summer.fengyu.plugin.market.PluginManifest.AiTool tool) {
        return packages.find(pluginId)
                .map(manifest -> schemaToString(manifest.inputSchemaFor(tool.method())))
                .orElse("{\"type\":\"object\",\"properties\":{}}");
    }

    /** Serialize a JsonNode schema to a String once, at the Spring-AI boundary (null → empty object). */
    private String schemaToString(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) return "{\"type\":\"object\",\"properties\":{}}";
        try { return json.writeValueAsString(node); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
    }

    private String quote(String value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ignored) { return "\"Plugin tool failed\""; }
    }

    private static ApprovalRequiredToolCallback approvalRequired(ToolCallback delegate) {
        return new ApprovalRequiredToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
            @Override public String call(String input) { return delegate.call(input); }
        };
    }

    private static AuditedToolCallback audited(ToolCallback delegate, ToolEffect effect) {
        return new AuditedToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
            @Override public org.springframework.ai.tool.metadata.ToolMetadata getToolMetadata() {
                return delegate.getToolMetadata();
            }
            @Override public String call(String input) { return delegate.call(input); }
            @Override public ToolEffect effect() { return effect; }
        };
    }

    public record ToolDescriptor(String id, String pluginId, String name, String description,
            String inputSchema, String outputSchema, String revision, String localizedDescription) {}
}
