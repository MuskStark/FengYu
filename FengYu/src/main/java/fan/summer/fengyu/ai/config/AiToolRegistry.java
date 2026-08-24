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
import fan.summer.fengyu.ai.tools.JsonSchemaContractValidator;
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
import java.util.LinkedHashMap;
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
    /** Optional guard whose PostToolUse hooks observe every chat-driven tool call. */
    private final fan.summer.fengyu.ai.tools.ToolGuardService toolGuard;
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

    /** Production constructor — the guard fires PostToolUse hooks for chat-driven calls. */
    public AiToolRegistry(List<FengYuTool> tools, PluginPackageService packages,
            PluginProcessManager processes, ObjectProvider<SyncMcpToolCallbackProvider> mcpProvider,
            ObjectProvider<WorkflowService> workflowProvider,
            ObjectProvider<WorkflowExecutionService> workflowExecutionProvider,
            McpRuntimeManager mcpRuntime,
            fan.summer.fengyu.ai.tools.ToolGuardService toolGuard) {
        this(tools, packages, processes, mcpProvider, workflowProvider, workflowExecutionProvider,
                mcpRuntime, AiConfigServiceHeadless::isComputerUseEnabled, toolGuard);
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
        this(tools, packages, processes, mcpProvider, workflowProvider, workflowExecutionProvider,
                mcpRuntime, computerUseEnabled, null);
    }

    /** Widest constructor — the guard observes finished chat tool calls (agent runs fire their own). */
    AiToolRegistry(List<FengYuTool> tools, PluginPackageService packages,
            PluginProcessManager processes, ObjectProvider<SyncMcpToolCallbackProvider> mcpProvider,
            ObjectProvider<WorkflowService> workflowProvider,
            ObjectProvider<WorkflowExecutionService> workflowExecutionProvider,
            McpRuntimeManager mcpRuntime, java.util.function.BooleanSupplier computerUseEnabled,
            fan.summer.fengyu.ai.tools.ToolGuardService toolGuard) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (FengYuTool toolBean : tools) {
            for (ToolCallback callback : ToolCallbacks.from(toolBean)) {
                if (toolBean instanceof ToolEffectProvider effects) {
                    ToolEffect effect = effects.effectFor(callback.getToolDefinition().name());
                    callbacks.add(audited(callback,
                            effect == null ? ToolEffect.EXTERNAL : effect, toolGuard));
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
        this.toolGuard = toolGuard;
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
                callbacks.add(audited(callback, ToolEffect.EXTERNAL, toolGuard));
            }
        }
        if (mcpRuntime != null) {
            for (ToolCallback callback : mcpRuntime.callbacks()) {
                callbacks.add(audited(callback, ToolEffect.EXTERNAL, toolGuard));
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
        return uniqueToolNames(callbacks);
    }

    /** UI descriptors include stable ownership and output metadata absent from Spring's definition. */
    public List<ToolDescriptor> descriptors(String locale) {
        boolean computerUse = computerUseVisible();
        List<ToolDescriptor> descriptors = new ArrayList<>();
        for (ToolCallback callback : builtins) {
            var definition = hostFlowDefinition(callback.getToolDefinition());
            if (!computerUse && isComputerTool(definition.name())) continue;
            descriptors.add(descriptor("builtin:" + definition.name(), null, definition, null, null,
                    hostFlowNode(definition.name(), locale), retrySafe(callback)));
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
                String flowNode = nodeToString(ManifestI18n.flowNode(manifest, tool.name(), locale));
                ToolDefinition definition = ToolDefinition.builder()
                        .name(tool.name()).description(tool.description()).inputSchema(inputSchema).build();
                // Localized description is for frontend display only; the LLM still sees the English
                // `description` baked into the ToolDefinition above. Falls back to the English original
                // when the manifest ships no i18n override for this tool.
                String localized = ManifestI18n.aiToolDescription(manifest, tool.name(), locale);
                descriptors.add(descriptor(manifest.id() + ":" + tool.name(), manifest.id(),
                        definition, outputSchema, localized, flowNode, retrySafe(tool)));
            }
        }
        SyncMcpToolCallbackProvider provider = mcpProvider.getIfAvailable();
        if (provider != null) {
            for (ToolCallback callback : provider.getToolCallbacks()) {
                var definition = callback.getToolDefinition();
                descriptors.add(descriptor("mcp:" + definition.name(), "mcp", definition,
                        null, null, false));
            }
        }
        if (mcpRuntime != null) {
            for (ToolCallback callback : mcpRuntime.callbacks()) {
                var definition = callback.getToolDefinition();
                descriptors.add(descriptor("mcp:" + definition.name(), "mcp", definition,
                        null, null, false));
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
                        "{\"type\":\"object\"}", null, false));
            }
        }
        return uniqueDescriptors(descriptors);
    }

    /**
     * Gives the model one unambiguous callback per name. Collection order is intentionally the
     * trust order: host tools, then installed plugins, startup MCP, dynamic MCP, then workflows.
     * A marketplace package or remote MCP server therefore cannot shadow a host capability merely
     * by declaring its name. Keeping this at the registry boundary also makes the chat and agent
     * catalogs follow the same collision rule.
     */
    private static List<ToolCallback> uniqueToolNames(List<ToolCallback> candidates) {
        Map<String, ToolCallback> unique = new LinkedHashMap<>();
        for (ToolCallback candidate : candidates) {
            String name = candidate.getToolDefinition().name();
            if (name != null && !name.isBlank()) unique.putIfAbsent(name, candidate);
        }
        return List.copyOf(unique.values());
    }

    /** Mirrors {@link #uniqueToolNames(List)} so the UI describes the exact executable catalog. */
    private static List<ToolDescriptor> uniqueDescriptors(List<ToolDescriptor> candidates) {
        Map<String, ToolDescriptor> unique = new LinkedHashMap<>();
        for (ToolDescriptor candidate : candidates) {
            if (candidate.name() != null && !candidate.name().isBlank()) {
                unique.putIfAbsent(candidate.name(), candidate);
            }
        }
        return List.copyOf(unique.values());
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

    /**
     * The request-bound flow tool of the Flowise-style builder chat: exposes the flow the
     * conversation is attached to as {@code run_current_flow} — DRAFT or published — so the
     * model converses with the flow under construction in the ordinary chat tool-call loop.
     * User-scoped like every workflow read; unknown ids throw IllegalArgumentException
     * (the controller fails the chat request fast).
     */
    public ToolCallback boundWorkflowTool(String workflowId) {
        WorkflowService workflowService = workflowProvider == null ? null : workflowProvider.getIfAvailable();
        WorkflowExecutionService executionService = workflowExecutionProvider == null
                ? null : workflowExecutionProvider.getIfAvailable();
        if (workflowService == null || executionService == null) {
            throw new IllegalArgumentException("Workflow tools are not available");
        }
        var workflow = workflowService.get(workflowId);
        return new AuditedToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder()
                    .name("run_current_flow")
                    .description(boundWorkflowToolDescription(workflow))
                    .inputSchema(workflowService.inputSchemaJson(workflow))
                    .build();

            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public ToolEffect effect() { return ToolEffect.EXTERNAL; }
            @Override public String call(String input) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = json.readValue(input == null ? "{}" : input, Map.class);
                    return executionService.executeForAi(workflow.id(), args, false);
                } catch (Exception error) {
                    return "{\"success\":false,\"error\":" + quote(String.valueOf(error.getMessage())) + "}";
                }
            }
        };
    }

    private static String boundWorkflowToolDescription(
            fan.summer.fengyu.ai.workflow.WorkflowDefinition workflow) {
        String publication = workflow.published() ? "published" : "draft";
        String detail = workflow.description() == null || workflow.description().isBlank()
                ? "" : ": " + workflow.description();
        return "Run the CURRENT flow '" + workflow.name() + "' (" + publication
                + ") the user is working on" + detail
                + ". Prefer this over other workflow tools when the user refers to 'the flow'"
                + " or 'this flow'; execute it with the inputs it declares.";
    }

    private static String workflowToolName(String id) {
        return "run_workflow_" + id.replace('-', '_');
    }

    private static String workflowToolDescription(String name, String description) {
        String detail = description == null || description.isBlank() ? "" : ": " + description;
        return "Run the published FengYu workflow '" + name + "'" + detail;
    }

    private ToolDescriptor descriptor(String id, String pluginId, ToolDefinition definition,
            String outputSchema, String localizedDescription, boolean retrySafe) {
        return descriptor(id, pluginId, definition, outputSchema, localizedDescription, null,
                retrySafe);
    }

    private ToolDescriptor descriptor(String id, String pluginId, ToolDefinition definition,
            String outputSchema, String localizedDescription, String flowNode, boolean retrySafe) {
        String revision = Integer.toUnsignedString(Objects.hash(
                definition.description(), definition.inputSchema(), outputSchema,
                localizedDescription, flowNode, retrySafe), 36);
        return new ToolDescriptor(id, pluginId, definition.name(), definition.description(),
                definition.inputSchema(), outputSchema, revision, localizedDescription,
                flowNode != null ? flowNode : hostFlowNode(definition.name()), retrySafe);
    }

    private static boolean retrySafe(ToolCallback callback) {
        return callback instanceof AuditedToolCallback audited && audited.retrySafe();
    }

    /** Host tools have no annotation-level default facility; keep execution defaults in schema. */
    private ToolDefinition hostFlowDefinition(ToolDefinition definition) {
        if (!"flow_if".equals(definition.name())) return definition;
        try {
            com.fasterxml.jackson.databind.node.ObjectNode schema =
                    (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(definition.inputSchema());
            com.fasterxml.jackson.databind.JsonNode operator = schema.path("properties").get("operator");
            if (operator instanceof com.fasterxml.jackson.databind.node.ObjectNode property) {
                property.put("default", "contains");
            }
            return ToolDefinition.builder().name(definition.name())
                    .description(definition.description()).inputSchema(schema.toString()).build();
        } catch (Exception ignored) {
            return definition;
        }
    }

    private static boolean retrySafe(
            fan.summer.fengyu.plugin.market.PluginManifest.AiTool tool) {
        return ToolEffect.from(tool.effect()) == ToolEffect.READ
                || Boolean.TRUE.equals(tool.idempotent());
    }

    /** Host-authored flow-node declarations; English is canonical, Chinese is localized. */
    private final Map<String, com.fasterxml.jackson.databind.JsonNode> hostFlowNodes =
            loadHostFlowNodes("/flow-nodes/builtin.json");
    private final Map<String, com.fasterxml.jackson.databind.JsonNode> hostFlowNodesZh =
            loadHostFlowNodeOverrides("/flow-nodes/builtin_zh.json");

    private Map<String, com.fasterxml.jackson.databind.JsonNode> loadHostFlowNodes(String resource) {
        try (var stream = getClass().getResourceAsStream(resource)) {
            if (stream == null) return Map.of();
            List<com.fasterxml.jackson.databind.JsonNode> nodes = json.readValue(stream,
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
            var byTool = new java.util.LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
            for (var node : nodes) {
                byTool.put(node.path("tool").asText(), node);
            }
            return java.util.Collections.unmodifiableMap(byTool);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, com.fasterxml.jackson.databind.JsonNode> loadHostFlowNodeOverrides(
            String resource) {
        try (var stream = getClass().getResourceAsStream(resource)) {
            if (stream == null) return Map.of();
            com.fasterxml.jackson.databind.JsonNode root = json.readTree(stream);
            if (!root.isObject()) return Map.of();
            var byTool = new java.util.LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
            root.fields().forEachRemaining(entry -> byTool.put(entry.getKey(), entry.getValue()));
            return java.util.Collections.unmodifiableMap(byTool);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String hostFlowNode(String toolName) {
        return hostFlowNode(toolName, ManifestI18n.DEFAULT_LOCALE);
    }

    private String hostFlowNode(String toolName, String locale) {
        com.fasterxml.jackson.databind.JsonNode canonical = hostFlowNodes.get(toolName);
        if (canonical == null) return null;
        if (locale != null && locale.toLowerCase(java.util.Locale.ROOT).startsWith("zh")) {
            com.fasterxml.jackson.databind.JsonNode override = hostFlowNodesZh.get(toolName);
            if (override != null) {
                return ManifestI18n.localizeFlowNode(canonical, override).toString();
            }
        }
        return canonical.toString();
    }

    private static String nodeToString(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) return null;
        return node.toString();
    }

    private ToolCallback pluginCallback(String pluginId,
            fan.summer.fengyu.plugin.market.PluginManifest.AiTool tool) {
        // T2-04 bullet 3: resolve the input schema ONCE from the referenced rpc method. The
        // serialized form is reused for both the LLM-facing ToolDefinition and the FileRef injector.
        return new AuditedToolCallback() {
            private final String inputSchema = resolveInputSchema(pluginId, tool);
            private final com.fasterxml.jackson.databind.JsonNode inputContract = parseSchema(inputSchema);
            private final com.fasterxml.jackson.databind.JsonNode outputContract = resolveOutputSchema(pluginId, tool);
            private final String outputSchema = outputContract == null ? null : schemaToString(outputContract);
            private final boolean acceptsRunSessionId = schemaDeclaresProperty(inputSchema, "sessionId");
            private final ToolDefinition definition = ToolDefinition.builder()
                    .name(tool.name()).description(tool.description()).inputSchema(inputSchema).build();

            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public ToolEffect effect() {
                // v2 makes effect mandatory; the manifest validator enforces non-null at install.
                return tool.effect() == null ? ToolEffect.EXTERNAL : ToolEffect.from(tool.effect());
            }
            @Override public boolean retrySafe() { return AiToolRegistry.retrySafe(tool); }
            @Override public String outputSchema() { return outputSchema; }

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
                    // Workflow runs carry file-class inputs as @file:<name> placeholders bound to
                    // run-scoped grants; swap in this plugin's FileRef just before dispatch.
                    injected = AiToolFileInjector.bindRunFilePlaceholders(injected, pluginId,
                            fan.summer.fengyu.ai.tools.RunFileContext.current());
                    // Canvas workflows and direct tool calls may leave the write-dir blank (the
                    // user has no granted path to type). Default it to the plugin's fixed output
                    // folder under its sandbox-writable data root — injectable as a plain path
                    // WITHOUT registering a grant, which would restart stateful plugin workers
                    // and destroy their in-memory sessions mid-flow. The blank-param check runs
                    // FIRST so tools without a write-dir parameter never pay the directory
                    // creation (defaultOutputPath createDirectories on every call).
                    if (AiToolFileInjector.blankWriteDirParam(injected, inputSchema) != null) {
                        try {
                            injected = AiToolFileInjector.fillDefaultOutputDir(injected, inputSchema,
                                    processes.defaultOutputPath(pluginId).toString());
                        } catch (Exception defaultDirUnavailable) {
                            // Leave the value as typed; the worker reports the write failure.
                        }
                    }
                    // Run-scoped state isolation for stateful plugins (P1-3): the runner
                    // stamps the run id around every step; plugins that accept a sessionId
                    // (the Excel AI tools) keep concurrent runs independent. Chat calls
                    // carry no run id and share the plugin's default session.
                    String runId = fan.summer.fengyu.ai.tools.AiRunContext.current();
                    if (runId != null && acceptsRunSessionId && !injected.containsKey("sessionId")) {
                        injected = new java.util.LinkedHashMap<>(injected);
                        injected.put("sessionId", runId);
                    }
                    JsonSchemaContractValidator.validateHostInput(injected, inputContract,
                            "Plugin tool '" + tool.name() + "' input");
                    long timeout = tool.timeoutSeconds() == null ? -1 : tool.timeoutSeconds();
                    String invocationId = fan.summer.fengyu.ai.tools.ToolInvocationContext.current();
                    Object result = invocationId == null
                            ? processes.invoke(pluginId, tool.method(), injected, timeout,
                                    AiToolLocaleContext.current())
                            : processes.invoke(pluginId, invocationId, tool.method(), injected,
                                    timeout, AiToolLocaleContext.current());
                    JsonSchemaContractValidator.validate(result, outputContract,
                            "Plugin tool '" + tool.name() + "' output");
                    String text = result instanceof String value ? value : json.writeValueAsString(result);
                    // Agent runs stamp AiRunContext and fire their own richer events — chat
                    // binds AiPermissionContext too, so isBound() is NOT the discriminator.
                    if (toolGuard != null && fan.summer.fengyu.ai.tools.AiRunContext.current() == null) {
                        toolGuard.observeToolResult(tool.name(), input, text, false, null);
                    }
                    return text;
                } catch (Exception error) {
                    String message = String.valueOf(error.getMessage());
                    if (toolGuard != null && fan.summer.fengyu.ai.tools.AiRunContext.current() == null) {
                        toolGuard.observeToolResult(tool.name(), input, message, true, null);
                    }
                    // AgentRunner owns terminal failure, replanning, and idempotency-aware retry.
                    // Returning a success-shaped string here would hide the exception from all
                    // three. Ordinary chat keeps the historical JSON error envelope.
                    if (fan.summer.fengyu.ai.tools.AiRunContext.current() != null) {
                        if (error instanceof RuntimeException runtime) throw runtime;
                        throw new IllegalStateException(message, error);
                    }
                    return "{\"success\":false,\"error\":" + quote(message) + "}";
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

    private com.fasterxml.jackson.databind.JsonNode resolveOutputSchema(String pluginId,
            fan.summer.fengyu.plugin.market.PluginManifest.AiTool tool) {
        return packages.find(pluginId)
                .map(manifest -> manifest.outputSchemaFor(tool.method()))
                .orElse(null);
    }

    private com.fasterxml.jackson.databind.JsonNode parseSchema(String schema) {
        try {
            return json.readTree(schema);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Host-only arguments must still obey the plugin's generated RPC contract. */
    private boolean schemaDeclaresProperty(String schema, String property) {
        try {
            return json.readTree(schema).path("properties").has(property);
        } catch (Exception ignored) {
            return false;
        }
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

    private static AuditedToolCallback audited(ToolCallback delegate, ToolEffect effect,
            fan.summer.fengyu.ai.tools.ToolGuardService toolGuard) {
        return new AuditedToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
            @Override public org.springframework.ai.tool.metadata.ToolMetadata getToolMetadata() {
                return delegate.getToolMetadata();
            }
            @Override public String call(String input) {
                // Outside agent runs (chat, workflows invoked as AI tools) this wrapper is the
                // only chokepoint that sees the finished result — fire observe hooks here.
                // Agent runs stamp AiRunContext around their steps and fire their own, richer
                // events (with runId), so skip to avoid double delivery. Chat binds
                // AiPermissionContext as well — that is NOT an agent run, so hooks must fire.
                if (toolGuard == null || fan.summer.fengyu.ai.tools.AiRunContext.current() != null) {
                    return delegate.call(input);
                }
                String name = delegate.getToolDefinition().name();
                try {
                    String result = delegate.call(input);
                    toolGuard.observeToolResult(name, input, result, false, null);
                    return result;
                } catch (RuntimeException failure) {
                    toolGuard.observeToolResult(name, input, failure.getMessage(), true, null);
                    throw failure;
                }
            }
            @Override public ToolEffect effect() { return effect; }
            @Override public String outputSchema() {
                return delegate instanceof AuditedToolCallback audited
                        ? audited.outputSchema() : null;
            }
        };
    }

    public record ToolDescriptor(String id, String pluginId, String name, String description,
            String inputSchema, String outputSchema, String revision, String localizedDescription,
            String flowNode, boolean retrySafe) {}
}
