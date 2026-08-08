package fan.summer.fengyu.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.ai.AiToolFileInjector;
import fan.summer.fengyu.ai.ChatFileContext;
import fan.summer.fengyu.ai.FengYuTool;
import fan.summer.fengyu.ai.tools.ApprovalRequiredTool;
import fan.summer.fengyu.ai.tools.ApprovalRequiredToolCallback;
import fan.summer.fengyu.ai.tools.AuditedToolCallback;
import fan.summer.fengyu.ai.tools.ToolEffect;
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
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    public AiToolRegistry(List<FengYuTool> tools, PluginPackageService packages,
            PluginProcessManager processes, ObjectProvider<SyncMcpToolCallbackProvider> mcpProvider) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (FengYuTool toolBean : tools) {
            for (ToolCallback callback : ToolCallbacks.from(toolBean)) {
                callbacks.add(toolBean instanceof ApprovalRequiredTool
                        ? approvalRequired(callback) : callback);
            }
        }
        this.builtins = List.copyOf(callbacks);
        this.packages = packages;
        this.processes = processes;
        this.mcpProvider = mcpProvider;
    }

    /** An immutable, internally consistent snapshot for one planning/execution operation. */
    public List<ToolCallback> callbacks() {
        List<ToolCallback> callbacks = new ArrayList<>(builtins);
        for (var manifest : packages.installed()) {
            if (!packages.isEnabled(manifest.id()) || manifest.aiTools() == null) continue;
            for (var tool : manifest.aiTools()) callbacks.add(pluginCallback(manifest.id(), tool));
        }
        SyncMcpToolCallbackProvider provider = mcpProvider.getIfAvailable();
        if (provider != null) {
            for (ToolCallback callback : provider.getToolCallbacks()) {
                callbacks.add(audited(callback, ToolEffect.EXTERNAL));
            }
        }
        return List.copyOf(callbacks);
    }

    /** UI descriptors include stable ownership and output metadata absent from Spring's definition. */
    /** UI descriptors include stable ownership and output metadata absent from Spring's definition. */
    public List<ToolDescriptor> descriptors(String locale) {
        List<ToolDescriptor> descriptors = new ArrayList<>();
        for (ToolCallback callback : builtins) {
            var definition = callback.getToolDefinition();
            descriptors.add(descriptor("builtin:" + definition.name(), null, definition, null, null));
        }
        for (var manifest : packages.installed()) {
            if (!packages.isEnabled(manifest.id()) || manifest.aiTools() == null) continue;
            for (var tool : manifest.aiTools()) {
                ToolDefinition definition = ToolDefinition.builder()
                        .name(tool.name()).description(tool.description()).inputSchema(tool.inputSchema()).build();
                // Localized description is for frontend display only; the LLM still sees the English
                // `description` baked into the ToolDefinition above. Falls back to the English original
                // when the manifest ships no i18n override for this tool.
                String localized = ManifestI18n.aiToolDescription(manifest, tool.name(), locale);
                descriptors.add(descriptor(manifest.id() + ":" + tool.name(), manifest.id(),
                        definition, tool.outputSchema(), localized));
            }
        }
        SyncMcpToolCallbackProvider provider = mcpProvider.getIfAvailable();
        if (provider != null) {
            for (ToolCallback callback : provider.getToolCallbacks()) {
                var definition = callback.getToolDefinition();
                descriptors.add(descriptor("mcp:" + definition.name(), "mcp", definition, null, null));
            }
        }
        return List.copyOf(descriptors);
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
        return new AuditedToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder()
                    .name(tool.name()).description(tool.description()).inputSchema(tool.inputSchema()).build();

            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public ToolEffect effect() {
                // An older third-party manifest has unknown side effects. Treat it conservatively
                // until the author declares an explicit effect instead of silently auto-approving it.
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
                            params, pluginId, tool.inputSchema(), ChatFileContext.current());
                    long timeout = tool.timeoutSeconds() == null ? -1 : tool.timeoutSeconds();
                    Object result = processes.invoke(pluginId, tool.method(), injected, timeout);
                    return result instanceof String text ? text : json.writeValueAsString(result);
                } catch (Exception error) {
                    return "{\"success\":false,\"error\":" + quote(String.valueOf(error.getMessage())) + "}";
                }
            }
        };
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
