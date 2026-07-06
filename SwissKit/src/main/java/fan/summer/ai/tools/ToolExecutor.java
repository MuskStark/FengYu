package fan.summer.ai.tools;

import fan.summer.zhiflow.api.ai.*;
import fan.summer.ai.util.JsonHelper;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Executes AI tool calls by dispatching to registered {@link AiTool} instances.
 *
 * <p>This class provides static utility methods for finding a tool by name,
 * executing it with the provided arguments, and streaming results back to an
 * {@link AiStreamCallback}. Tool execution is wrapped in error handling so
 * callers receive a well-formed {@link AiToolResult} even when exceptions occur.</p>
 *
 * <p>Typical usage for synchronous execution:</p>
 * <pre>{@code
 * AiToolResult result = ToolExecutor.execute("json_format", args);
 * }</pre>
 *
 * <p>For streaming execution that feeds results into a chat history:</p>
 * <pre>{@code
 * ToolExecutor.executeAndFeed(toolCalls, history, callback);
 * }</pre>
 *
 * @see AiTool
 * @see AiServiceProvider
 */
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private ToolExecutor() {}

    /**
     * Looks up the named tool from {@link AiServiceProvider} and executes it with
     * the given arguments.
     *
     * @param toolName the name of the tool to execute (must match {@link AiTool#getName()})
     * @param arguments a map of parameter names to values as supplied by the AI model
     * @return the result of the tool execution, or an error result if the tool is not
     *         found or execution throws an exception
     * @see AiServiceProvider#getTool(String)
     */
    public static AiToolResult execute(String toolName, Map<String, Object> arguments) {
        AiTool tool = AiServiceProvider.getTool(toolName);
        if (tool == null) {
            log.warn("Tool not found: {}", toolName);
            return AiToolResult.error(jsonError("Tool not found: " + toolName));
        }
        try {
            log.debug("Executing tool: name={}, arguments={}", toolName, arguments);
            return tool.execute(arguments);
        } catch (Exception e) {
            log.error("Tool execution error: tool={}, error={}", toolName, e.getMessage());
            return AiToolResult.error(jsonError("Tool execution error: " + e.getMessage()));
        }
    }

    private static String jsonError(String message) {
        return JsonHelper.toJson(Map.of("success", false, "error", message));
    }

    /**
     * Executes a list of tool calls sequentially, notifying the callback as each
     * call starts and finishes, and appending the results to the chat history.
     *
     * <p>This method is designed for streaming pipelines where the AI issues multiple
     * tool calls in sequence and each result must be fed back to maintain context.</p>
     *
     * @param toolCalls list of tool calls to execute in order
     * @param history   the chat message list to which tool-result messages will be appended
     * @param callback  the callback to notify of tool-call start and result events;
     *                  callbacks are invoked on the JavaFX Application Thread
     * @see AiStreamCallback
     */
    public static void executeAndFeed(List<AiToolCall> toolCalls,
                                      List<AiChatMessage> history,
                                      AiStreamCallback callback) {
        for (AiToolCall tc : toolCalls) {
            Platform.runLater(() -> callback.onToolCall(tc));
            log.info("Executing tool: name={}, args={}", tc.name(), tc.arguments());
            AiToolResult result = execute(tc.name(), tc.arguments());
            log.info("Tool result: success={}", result.success());
            Platform.runLater(() -> callback.onToolResult(tc.id(), result));
            history.add(AiChatMessage.toolResult(tc.id(), tc.name(), result.output()));
        }
    }
}
