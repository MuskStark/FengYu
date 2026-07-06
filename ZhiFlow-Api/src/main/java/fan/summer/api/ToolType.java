package fan.summer.api;

/**
 * Distinguishes built-in tools from externally-loaded plugins.
 *
 * <p>Built-in tools are registered programmatically via {@code BuiltinToolRegistrar}
 * and ship with the host application. External plugins are discovered at runtime
 * from JAR files dropped into the {@code plugins/} directory.</p>
 *
 * @see ZhiFlowPlugin#getType()
 */
public enum ToolType {
    /** Tool that ships with the host application and is registered directly in the registry. */
    BUILTIN("builtin"),

    /** Tool loaded at runtime from an external JAR placed in the {@code plugins/} directory. */
    PLUGIN("plugin");

    private final String id;

    ToolType(String id) { this.id = id; }

    /**
     * Returns the lowercase identifier used for serialisation and persistence.
     *
     * @return the type id, e.g. {@code "builtin"}
     */
    public String getId() { return id; }

    /**
     * Returns {@code true} if this type is {@link #BUILTIN}.
     *
     * @return {@code true} for {@link #BUILTIN}, {@code false} otherwise
     */
    public boolean isBuiltin() { return this == BUILTIN; }

    /**
     * Returns {@code true} if this type is {@link #PLUGIN}.
     *
     * @return {@code true} for {@link #PLUGIN}, {@code false} otherwise
     */
    public boolean isPlugin() { return this == PLUGIN; }
}
