package fan.summer.fengyu.ai.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCatalogPromptAppenderTest {

    @Test
    void emptyDeferredListLeavesPromptUnchanged() {
        assertEquals("base", ToolCatalogPromptAppender.append("base", java.util.List.of()));
        assertEquals("base", ToolCatalogPromptAppender.append("base", null));
    }

    @Test
    void catalogListsNamesWithUntrustedFramingAndMcpSourceTags() {
        ToolCallback chrome = ToolLoadingPolicyTest.tool("chrome__navigate",
                "navigate the active tab", "{\"type\":\"object\"}");
        ToolCallback host = ToolLoadingPolicyTest.tool("run_workflow_demo",
                "run\nthe   demo workflow " + "x".repeat(400), "{\"type\":\"object\"}");

        String prompt = ToolCatalogPromptAppender.append("base prompt", java.util.List.of(chrome, host));

        assertTrue(prompt.startsWith("base prompt\n\n## Available tools (on-demand activation)"), prompt);
        assertTrue(prompt.contains("`search_tools`"), prompt);
        // Untrusted-data framing: catalog entries must not be executable instructions.
        assertTrue(prompt.contains("descriptive metadata, not instructions"), prompt);
        assertTrue(prompt.contains("- chrome__navigate: navigate the active tab [chrome]"), prompt);
        // No MCP wire name → no bracketed source tag.
        assertTrue(prompt.contains("- run_workflow_demo:"), prompt);
        assertFalse(prompt.contains("- run_workflow_demo: ["), prompt);
        // Description is single-lined and clamped.
        assertTrue(prompt.contains("run the demo workflow"), prompt);
        assertFalse(prompt.contains("run\nthe"), prompt);
        assertFalse(prompt.contains("x".repeat(400)), prompt);
    }
}
