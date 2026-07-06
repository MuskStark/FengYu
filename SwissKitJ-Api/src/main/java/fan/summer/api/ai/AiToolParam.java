package fan.summer.api.ai;

import java.util.List;

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
 * @param enumValues  optional allowed values emitted as {@code enum:[...]} to the model;
 *                    empty list means unconstrained (never null after compact-constructor normalization)
 * @see AiTool#getParameters()
 */
public record AiToolParam(
    String name,
    String type,
    String description,
    boolean required,
    List<String> enumValues
) {
    public AiToolParam {
        if (enumValues == null) enumValues = List.of();
    }

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
        return new AiToolParam(name, type, description, required, List.of());
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
        return new AiToolParam(name, type, description, true, List.of());
    }

    /**
     * Creates a parameter descriptor with an explicit set of allowed values.
     *
     * @param name        the parameter name
     * @param type        the JSON schema type
     * @param description a human-readable description
     * @param required    whether the parameter is mandatory
     * @param enumValues  the allowed values, emitted as {@code enum:[...]}
     * @return a new {@code AiToolParam}
     */
    public static AiToolParam of(String name, String type, String description, boolean required, List<String> enumValues) {
        return new AiToolParam(name, type, description, required, enumValues);
    }
}