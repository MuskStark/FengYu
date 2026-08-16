package fan.summer.fengyu.ai.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BoundToolsContextTest {

    @AfterEach
    void clear() {
        BoundToolsContext.clear();
    }

    private ToolCallback named(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(
                ToolDefinition.builder().name(name).description("").inputSchema("{}").build());
        return callback;
    }

    @Test
    void emptyContextReturnsRegistrySnapshotUnchanged() {
        List<ToolCallback> registry = List.of(named("a"), named("b"));
        assertEquals(registry, BoundToolsContext.mergeWith(registry));
    }

    @Test
    void boundToolsArePrependedAndWinNameCollisions() {
        ToolCallback bound = named("run_current_flow");
        BoundToolsContext.set(List.of(bound));
        ToolCallback duplicate = named("run_current_flow");
        ToolCallback other = named("web_search");

        List<ToolCallback> merged = BoundToolsContext.mergeWith(List.of(duplicate, other));

        assertEquals(2, merged.size());
        assertEquals("run_current_flow", merged.get(0).getToolDefinition().name());
        // The bound instance wins over the registry duplicate…
        assertEquals(bound, merged.get(0));
        // …and unrelated registry tools pass through untouched.
        assertEquals("web_search", merged.get(1).getToolDefinition().name());
    }

    @Test
    void nullBindingBehavesAsEmpty() {
        BoundToolsContext.set(null);
        assertEquals(List.of(), BoundToolsContext.current());
    }
}
