package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.ai.FengYuTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host-side {@code todo_write} tool: the model's visible plan for long multi-step tasks
 * (terminal coding-agent practice — ZCode / Claude Code). The whole list is replaced on
 * every call; the UI renders the returned state as a checklist so the user can follow
 * progress. Conversation-scoped through {@link ConversationContext}; hidden from the tool
 * catalog for turns without a conversation id (legacy flow turns).
 */
@Component
public class TodoTool implements FengYuTool, ToolEffectProvider {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TodoState state;

    public TodoTool(TodoState state) {
        this.state = state;
    }

    @Override
    public ToolEffect effectFor(String toolName) {
        return "todo_write".equals(toolName) ? ToolEffect.READ : null;
    }

    /** One step as the model supplies it. */
    public record TodoInput(
            @ToolParam(description = "Short imperative step description.") String content,
            @ToolParam(required = false,
                       description = "One of: pending, in_progress, completed (default pending).")
            String status) {}

    @Tool(name = "todo_write",
          description = "Update the visible task checklist for this conversation. Send the FULL "
                  + "list every call (it replaces the previous one). Use it whenever a task needs "
                  + "3+ steps: mark exactly one step in_progress while working on it, mark steps "
                  + "completed as soon as they are done, and add newly discovered steps. The user "
                  + "sees this list live, so keep it an honest reflection of the plan.")
    public String todoWrite(
            @ToolParam(description = "The complete task list (all steps, in order).")
            List<TodoInput> todos) {
        Long conversationId = ConversationContext.current();
        if (conversationId == null) {
            return error("No conversation is bound; the todo list is unavailable");
        }
        try {
            List<TodoState.TodoItem> items = todos == null ? List.of() : todos.stream()
                    .map(item -> new TodoState.TodoItem(
                            item == null ? null : item.content(),
                            item == null ? null : item.status()))
                    .toList();
            List<TodoState.TodoItem> saved = state.replace(conversationId, items);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            List<Map<String, Object>> rendered = new ArrayList<>();
            for (TodoState.TodoItem item : saved) {
                rendered.add(Map.of("content", item.content(), "status", item.status()));
            }
            result.put("todos", rendered);
            return JSON.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            return error("tool result serialization failed");
        }
    }

    private static String error(String message) {
        return "{\"success\":false,\"error\":" + quote(message) + "}";
    }

    private static String quote(String value) {
        try {
            return JSON.writeValueAsString(value == null ? "" : value);
        } catch (Exception e) {
            return "\"todo tool failed\"";
        }
    }
}
