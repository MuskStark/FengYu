package fan.summer.fengyu.plugin.market;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Manifest stored at the root of every .fyp package (schema v2).
 *
 * <p>v2 freezes the worker contract: the backend command is fixed to {@code java -jar
 * backend/worker.jar} speaking JSON-RPC 2.0 (no {@code command}/{@code protocol} in the manifest),
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
    Map<String, LocaleOverride> i18n
) {
    public record Ui(String entry) {}

    /** Declares the out-of-process worker. The command is fixed; only the default timeout is tunable. */
    public record Backend(Long callTimeoutSeconds) {}

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
     * mandatory authorization metadata (read / write / external).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AiTool(String name, String description, String method, Long timeoutSeconds, String effect) {
        /** Convenience constructor for callers that omit the per-tool timeout. */
        public AiTool(String name, String description, String method, String effect) {
            this(name, description, method, null, effect);
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
        Map<String, AiToolOverride> aiTools
    ) {}

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
                permissions, homepage, official, rpc, aiTools, null);
    }
}
