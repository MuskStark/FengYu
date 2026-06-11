package fan.summer.api.ai;

/**
 * Describes a single parameter accepted by an {@link AiTool}.
 *
 * <p>Parameters are included in the JSON schema sent to the model so it knows
 * what arguments to provide when calling the tool.</p>
 *
 * @param name        the parameter name (used as the key in the arguments map)
 * @param type        the JSON schema type, e.g. {@code "string"}, {@code "integer"}, {@code "boolean"}
 * @param description a human-readable description shown to the model
 * @param required    whether this parameter must be provided
 * @see AiTool#getParameters()
 */
public record AiToolParam(
    String name,
    String type,
    String description,
    boolean required
) {
    /**
     * Creates a parameter descriptor.
     *
     * @param name        the parameter name
     * @param type        the JSON schema type
     * @param description a human-readable description
     * @param required    whether the parameter is mandatory
     * @return a new {@code AiToolParam}
     */
    public static AiToolParam of(String name, String type, String description, boolean required) {
        return new AiToolParam(name, type, description, required);
    }

    /**
     * Creates a required parameter descriptor (convenience overload).
     *
     * @param name        the parameter name
     * @param type        the JSON schema type
     * @param description a human-readable description
     * @return a new required {@code AiToolParam}
     */
    public static AiToolParam of(String name, String type, String description) {
        return new AiToolParam(name, type, description, true);
    }
}