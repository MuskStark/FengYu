package fan.summer.ai.tools;

import fan.summer.api.ai.*;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private ToolExecutor() {}

    public static AiToolResult execute(String toolName, Map<String, Object> arguments) {
        AiTool tool = AiServiceProvider.getTool(toolName);
        if (tool == null) {
            log.warn("Tool not found: {}", toolName);
            return AiToolResult.error("Tool not found: " + toolName);
        }
        try {
            log.debug("Executing tool: name={}, arguments={}", toolName, arguments);
            return tool.execute(arguments);
        } catch (Exception e) {
            log.error("Tool execution error: tool={}, error={}", toolName, e.getMessage());
            return AiToolResult.error("Tool execution error: " + e.getMessage());
        }
    }

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
