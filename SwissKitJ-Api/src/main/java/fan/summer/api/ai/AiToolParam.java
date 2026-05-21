package fan.summer.api.ai;

/**
 * Describes a single parameter for an {@link AiTool}.
 */
public record AiToolParam(
    String name,
    String type,
    String description,
    boolean required
) {
    public static AiToolParam of(String name, String type, String description, boolean required) {
        return new AiToolParam(name, type, description, required);
    }

    public static AiToolParam of(String name, String type, String description) {
        return new AiToolParam(name, type, description, true);
    }
}