package fan.summer.fengyu.api.ai;

/**
 * An immutable record representing the outcome of executing an {@link AiTool}.
 *
 * <p>The result is fed back to the model as a tool-result message so it can
 * incorporate the output into its response.</p>
 *
 * @param success {@code true} if the tool executed successfully, {@code false} on error
 * @param output  the tool's output text on success, or an error message on failure
 * @see AiTool#execute(java.util.Map)
 * @see AiChatMessage#toolResult(String, String, String)
 */
public record AiToolResult(
    boolean success,
    String output
) {
    /**
     * Creates a successful result with the given output.
     *
     * @param output the tool's output text
     * @return a success result
     */
    public static AiToolResult success(String output) {
        return new AiToolResult(true, output);
    }

    /**
     * Creates an error result with the given message.
     *
     * @param message a description of what went wrong
     * @return an error result
     */
    public static AiToolResult error(String message) {
        return new AiToolResult(false, message);
    }
}
