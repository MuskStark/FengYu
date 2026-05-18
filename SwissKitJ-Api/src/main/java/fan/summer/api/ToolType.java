package fan.summer.api;

/**
 * Distinguishes built-in tools from externally-loaded plugins.
 */
public enum ToolType {
    BUILTIN("builtin"),
    PLUGIN("plugin");

    private final String id;

    ToolType(String id) { this.id = id; }

    /** Lowercase identifier matching the legacy string (e.g. "builtin"). */
    public String getId() { return id; }

    public boolean isBuiltin() { return this == BUILTIN; }

    public boolean isPlugin() { return this == PLUGIN; }
}
