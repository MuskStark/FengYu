package fan.summer.fengyu.plugin.market;

import java.util.List;

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
    List<AiTool> aiTools
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
}
