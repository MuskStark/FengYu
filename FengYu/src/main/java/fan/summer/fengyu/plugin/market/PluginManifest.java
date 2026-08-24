package fan.summer.fengyu.plugin.market;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Manifest stored at the root of every .fyp package (schema v2).
 *
 * <p>v2 freezes the worker contract: the backend command is selected from a host-owned runtime
 * allowlist ({@code java}, {@code python}, or {@code go}) and speaks JSON-RPC 2.0 (no arbitrary
 * {@code command} in the manifest),
 * input/output schemas live once per RPC method on {@link Rpc#methods()}, and {@link AiTool}s
 * reference those methods by name rather than duplicating schemas inline. The host accepts
 * {@code schemaVersion == 2} only.
 */
public record PluginManifest(
    int schemaVersion,
    String id,
    String name,
    String description,
    String version,
    String author,
    String icon,
    String category,
    Ui ui,
    Backend backend,
    List<String> permissions,
    String homepage,
    boolean official,
    Rpc rpc,
    List<AiTool> aiTools,
    Map<String, LocaleOverride> i18n,
    JsonNode flowNodes,
    Engines engines
) {
    public record Ui(String entry) {}

    /**
     * Declares the out-of-process worker. Runtime selects one host-owned conventional artifact;
     * protocolVersion opts into the startup handshake. Both are nullable for legacy Java packages.
     */
    public record Backend(String runtime, Integer protocolVersion, Long callTimeoutSeconds,
                          ResourceLimits resources) {
        public Backend(String runtime, Integer protocolVersion, Long callTimeoutSeconds) {
            this(runtime, protocolVersion, callTimeoutSeconds, null);
        }

        public Backend(Long callTimeoutSeconds) {
            this(null, null, callTimeoutSeconds, null);
        }
    }

    /** Optional hard worker-tree ceilings monitored by the host. */
    public record ResourceLimits(Long memoryMb, Integer maxProcesses) {}

    /** Host compatibility constraints. {@code fengyu} uses a bounded SemVer range. */
    public record Engines(String fengyu) {}

    /** The shared method table; every UI RPC and every {@link AiTool#method()} must resolve here. */
    public record Rpc(Map<String, RpcMethod> methods) {}

    /**
     * One RPC method. {@code inputSchema}/{@code outputSchema} are JSON-Schema OBJECT nodes parsed
     * directly by Jackson (never escaped strings), so no re-parsing is needed at the call site.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RpcMethod(
        String description,
        JsonNode inputSchema,
        JsonNode outputSchema,
        Long timeoutSeconds
    ) {}

    /**
     * An AI-facing tool. References an {@link Rpc#methods()} entry by {@code method}; the
     * input/output schemas are resolved from that method, not duplicated here. {@code effect} is
     * mandatory authorization metadata (read / write / external). {@code idempotent} lets a
     * write/external capability explicitly opt into identical-invocation retries; read tools are
     * retry-safe regardless.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AiTool(String name, String description, String method, Long timeoutSeconds,
                         String effect, Boolean idempotent) {
        /** Backward-compatible constructor for callers predating idempotency metadata. */
        public AiTool(String name, String description, String method, Long timeoutSeconds,
                      String effect) {
            this(name, description, method, timeoutSeconds, effect, null);
        }

        /** Convenience constructor for callers that omit the per-tool timeout. */
        public AiTool(String name, String description, String method, String effect) {
            this(name, description, method, null, effect, null);
        }
    }

    /**
     * Optional locale override for a manifest's display strings. Every field is independently
     * nullable; {@link ManifestI18n} falls back to the manifest's top-level (default, English)
     * field whenever an override is absent, so a plugin can translate only the strings it cares
     * about. {@code aiTools} is keyed by tool {@code name} (stable across versions) rather than
     * array position, mirroring how the registry looks tools up.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LocaleOverride(
        String name,
        String description,
        Map<String, AiToolOverride> aiTools,
        JsonNode flowNodes
    ) {
        public LocaleOverride(String name, String description,
                Map<String, AiToolOverride> aiTools) {
            this(name, description, aiTools, null);
        }
    }

    /** Locale override for a single AI tool's display strings (frontend only; never sent to the LLM). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AiToolOverride(String name, String description) {}

    /**
     * Resolve the input-schema OBJECT node for an rpc method. Null-safe: returns {@code null} when
     * the manifest has no {@code rpc} block, no such method, or the method declares no inputSchema.
     * This is the single place AI/UI input schemas are resolved — there is no stored JSON string
     * to re-parse.
     */
    public JsonNode inputSchemaFor(String method) {
        if (rpc() == null || rpc().methods() == null) return null;
        RpcMethod m = rpc().methods().get(method);
        return m == null ? null : m.inputSchema();
    }

    /**
     * The flow-node descriptor bound to one aiTool name, or null when the plugin declares
     * no {@code flowNodes} entry for it. The canvas renders node inputs/outputs from this
     * explicit configuration instead of deriving a form from the tool's JSON Schema.
     */
    public JsonNode flowNodeFor(String toolName) {
        if (flowNodes == null || !flowNodes.isArray()) return null;
        for (JsonNode node : flowNodes) {
            if (node.isObject() && toolName != null && toolName.equals(node.path("tool").asText())) {
                return node;
            }
        }
        return null;
    }

    /** Resolve the output-schema node for an rpc method, null-safe (mirrors {@link #inputSchemaFor}). */
    public JsonNode outputSchemaFor(String method) {
        if (rpc() == null || rpc().methods() == null) return null;
        RpcMethod m = rpc().methods().get(method);
        return m == null ? null : m.outputSchema();
    }

    /**
     * Convenience constructor for manifests that omit the {@code i18n} block. Existing
     * single-language packages deserialize through the canonical Jackson path; this constructor is
     * kept for callers that build a manifest in code.
     */
    public PluginManifest(int schemaVersion, String id, String name, String description, String version,
            String author, String icon, String category, Ui ui, Backend backend, List<String> permissions,
            String homepage, boolean official, Rpc rpc, List<AiTool> aiTools) {
        this(schemaVersion, id, name, description, version, author, icon, category, ui, backend,
                permissions, homepage, official, rpc, aiTools, null, null, null);
    }

    /** Backward-compatible constructor for callers predating host-version constraints. */
    public PluginManifest(int schemaVersion, String id, String name, String description,
            String version, String author, String icon, String category, Ui ui, Backend backend,
            List<String> permissions, String homepage, boolean official, Rpc rpc,
            List<AiTool> aiTools, Map<String, LocaleOverride> i18n, JsonNode flowNodes) {
        this(schemaVersion, id, name, description, version, author, icon, category, ui, backend,
                permissions, homepage, official, rpc, aiTools, i18n, flowNodes, null);
    }
}
