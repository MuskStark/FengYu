package fan.summer.fengyu.ai;

import java.util.Map;

/**
 * An immutable record representing the outcome of executing a tool call.
 *
 * <p>The result is fed back to the model as a tool-result message so it can
 * incorporate the output into its response.</p>
 *
 * @param success {@code true} if the tool executed successfully, {@code false} on error
 * @param output  the tool's output text on success, or an error message on failure
 * @param ui      additive MCP Apps projection ({@code resourceUri}, structured content, CSP
 *                hints…); null for every non-UI tool. NEVER fed back to the model — it exists
 *                only so the SSE {@code tool} result event can carry the render descriptor.
 * @see AiChatMessage#toolResult(String, String, String)
 */
public record AiToolResult(
    boolean success,
    String output,
    Map<String, Object> ui
) {
    /** Back-compat shape: a result without UI metadata. */
    public AiToolResult(boolean success, String output) {
        this(success, output, null);
    }

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
