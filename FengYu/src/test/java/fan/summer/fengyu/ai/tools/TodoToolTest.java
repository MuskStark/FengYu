package fan.summer.fengyu.ai.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The conversation-scoped todo tool (4.1.0): replace-whole-list semantics + binding guard. */
class TodoToolTest {

    @AfterEach
    void clear() {
        ConversationContext.clear();
    }

    @Test
    void replacesWholeListAndNormalizesStatus() {
        ConversationContext.set(42L);
        TodoState state = new TodoState();
        TodoTool tool = new TodoTool(state);

        String first = tool.todoWrite(List.of(
                new TodoTool.TodoInput("step one", "in_progress"),
                new TodoTool.TodoInput("step two", "completed")));
        assertTrue(first.contains("\"success\":true"));
        assertTrue(first.contains("\"content\":\"step one\""));
        assertTrue(first.contains("\"status\":\"in_progress\""));

        String second = tool.todoWrite(List.of(
                new TodoTool.TodoInput("only step", "weird-status")));
        assertTrue(second.contains("\"content\":\"only step\""));
        assertTrue(second.contains("\"status\":\"pending\""), "unknown statuses normalize to pending");
        assertFalse(second.contains("step one"), "the list is replaced wholesale");
        assertTrue(state.list(42L).size() == 1);
    }

    @Test
    void withoutConversationBindingTheToolErrors() {
        ConversationContext.clear();
        TodoTool tool = new TodoTool(new TodoState());
        String result = tool.todoWrite(List.of(new TodoTool.TodoInput("step", null)));
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("No conversation"));
    }

    /** Regression: the conversation map is bounded — long uptimes with many conversations
     *  evict the oldest instead of growing without limit. */
    @Test
    void conversationMapIsBoundedOldestFirst() {
        TodoState state = new TodoState();
        state.replace(1L, List.of(new TodoState.TodoItem("first", null)));
        for (long id = 2L; id <= TodoState.MAX_CONVERSATIONS + 1; id++) {
            state.replace(id, List.of(new TodoState.TodoItem("c" + id, null)));
        }
        assertTrue(state.list(1L).isEmpty(), "the oldest conversation's list is evicted");
        assertEquals(1, state.list(TodoState.MAX_CONVERSATIONS + 1L).size());
    }

    @Test
    void effectIsReadSoItNeverNeedsApproval() {
        TodoTool tool = new TodoTool(new TodoState());
        assertTrue(tool.effectFor("todo_write") == ToolEffect.READ);
        assertFalse(ToolApprovalPolicy.requiresApproval(
                new FakeAudited("todo_write", ToolEffect.READ),
                AiPermissionMode.ASK_FOR_APPROVAL, "{}"));
    }

    /** Minimal audited callback reused for the approval-policy assertion. */
    static final class FakeAudited implements AuditedToolCallback {
        private final org.springframework.ai.tool.definition.ToolDefinition definition;
        private final ToolEffect effect;
        FakeAudited(String name, ToolEffect effect) {
            this.definition = org.springframework.ai.tool.definition.DefaultToolDefinition.builder()
                    .name(name).description(name).inputSchema("{\"type\":\"object\"}").build();
            this.effect = effect;
        }
        @Override public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() { return definition; }
        @Override public ToolEffect effect() { return effect; }
        @Override public String call(String input) { return input; }
    }
}
