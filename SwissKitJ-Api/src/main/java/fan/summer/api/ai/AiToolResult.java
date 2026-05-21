package fan.summer.api.ai;

/**
 * Result of executing a tool call.
 */
public record AiToolResult(
    boolean success,
    String output
) {
    public static AiToolResult success(String output) {
        return new AiToolResult(true, output);
    }

    public static AiToolResult error(String message) {
        return new AiToolResult(false, message);
    }
}
