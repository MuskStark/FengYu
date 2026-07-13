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
    public record Backend(String command, String protocol) {}
    public record AiTool(String name, String description, String inputSchema, String method) {}
}
