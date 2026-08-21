package fan.summer.fengyu.ai.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolLoadingPolicyTest {

    static ToolCallback tool(String name, String description, String schema) {
        ToolDefinition definition = DefaultToolDefinition.builder()
                .name(name).description(description).inputSchema(schema).build();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public String call(String toolInput) { return "ok"; }
        };
    }

    @Test
    void autoModeGatesOnThresholdAndOverridesWin() {
        assertTrue(ToolLoadingPolicy.dynamicLoading("auto", 25, 26));
        assertFalse(ToolLoadingPolicy.dynamicLoading("auto", 25, 25));
        assertTrue(ToolLoadingPolicy.dynamicLoading("always", 25, 1));
        assertFalse(ToolLoadingPolicy.dynamicLoading("off", 25, 1_000));
        // Unknown/garbage modes fall back to auto, never to a broken state.
        assertTrue(ToolLoadingPolicy.dynamicLoading(null, 5, 6));
        assertFalse(ToolLoadingPolicy.dynamicLoading("nonsense", 5, 5));
    }

    @Test
    void thresholdIsClampedToASaneRange() {
        assertEquals(ToolLoadingPolicy.DEFAULT_THRESHOLD, ToolLoadingPolicy.clampThreshold(null));
        assertEquals(5, ToolLoadingPolicy.clampThreshold(0));
        assertEquals(500, ToolLoadingPolicy.clampThreshold(100_000));
        assertEquals(40, ToolLoadingPolicy.clampThreshold(40));
    }

    @Test
    void partitionKeepsCheapCoreAndActivatedToolsOnly() {
        ToolCallback cheap = tool("json_format", "format json", "{\"type\":\"object\"}");
        // A realistic browser-tool schema: properties + descriptions push the estimate well
        // past CORE_TOOL_MAX_TOKENS (400), unlike the tiny schemas cheap tools carry.
        StringBuilder heavySchema = new StringBuilder("{\"type\":\"object\",\"properties\":{");
        for (int i = 0; i < 20; i++) {
            heavySchema.append("\"option").append(i)
                    .append("\":{\"type\":\"string\",\"description\":\"browser option number ")
                    .append(i).append(" with a verbose explanation of its effects\"},");
        }
        heavySchema.append("\"url\":{\"type\":\"string\"}},\"required\":[\"url\"]}");
        ToolCallback heavy = tool("browser_navigate", "navigate", heavySchema.toString());
        assertTrue(ToolLoadingPolicy.definitionTokens(heavy.getToolDefinition())
                        > ToolLoadingPolicy.CORE_TOOL_MAX_TOKENS,
                "test fixture must exceed the core threshold");
        ToolActivationState state = new ToolActivationState(Set.of("json_format", "browser_navigate"));

        List<ToolCallback> attached = ToolLoadingPolicy.attachedTools(List.of(cheap, heavy), state);
        assertEquals(List.of("json_format"), names(attached));
        assertEquals(List.of("browser_navigate"), names(ToolLoadingPolicy.deferredTools(List.of(cheap, heavy), state)));

        state.activate("browser_navigate");
        assertEquals(Set.of("json_format", "browser_navigate"),
                Set.copyOf(names(ToolLoadingPolicy.attachedTools(List.of(cheap, heavy), state))));
        assertTrue(ToolLoadingPolicy.deferredTools(List.of(cheap, heavy), state).isEmpty());
    }

    @Test
    void modeNormalizationAcceptsAliases() {
        assertEquals(ToolLoadingPolicy.MODE_ALWAYS, ToolLoadingPolicy.normalizeMode("ON"));
        assertEquals(ToolLoadingPolicy.MODE_OFF, ToolLoadingPolicy.normalizeMode("false"));
        assertEquals(ToolLoadingPolicy.MODE_AUTO, ToolLoadingPolicy.normalizeMode(" "));
    }

    private static List<String> names(List<ToolCallback> tools) {
        return tools.stream().map(t -> t.getToolDefinition().name()).toList();
    }
}
