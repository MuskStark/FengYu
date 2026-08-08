package fan.summer.fengyu.plugin.market;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/** Manifest stored at the root of every .fyp package. */
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
    List<AiTool> aiTools,
    Map<String, LocaleOverride> i18n
) {
    public record Ui(String entry) {}
    public record Backend(String command, String protocol, Long callTimeoutSeconds) {
        /** Backwards-compatible constructor for callers that omit the timeout. */
        public Backend(String command, String protocol) { this(command, protocol, null); }
    }
    public record AiTool(String name, String description, String inputSchema, String outputSchema,
                         String method, Long timeoutSeconds, String effect) {
        /** Backwards-compatible constructor for callers and manifests that omit output metadata. */
        public AiTool(String name, String description, String inputSchema, String method, Long timeoutSeconds) {
            this(name, description, inputSchema, null, method, timeoutSeconds, null);
        }
        /** Backwards-compatible constructor for callers that omit the timeout. */
        public AiTool(String name, String description, String inputSchema, String method) {
            this(name, description, inputSchema, null, method, null, null);
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
     * Backwards-compatible constructor for manifests that omit the {@code i18n} block. Existing
     * single-language packages deserialize through the canonical Jackson path; this constructor is
     * kept for any caller that builds a manifest in code.
     */
    public PluginManifest(int schemaVersion, String id, String name, String description, String version,
            String author, String icon, String category, Ui ui, Backend backend, List<String> permissions,
            String homepage, boolean official, List<AiTool> aiTools) {
        this(schemaVersion, id, name, description, version, author, icon, category, ui, backend,
                permissions, homepage, official, aiTools, null);
    }
}
