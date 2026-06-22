package fan.summer.ai.tools;

import fan.summer.api.ai.AiServiceProvider;
import fan.summer.api.ai.AiTool;
import fan.summer.api.ai.AiToolParam;
import fan.summer.api.ai.AiToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolSchemaBuilderLocalTest {

    @AfterEach
    void reset() {
        AiServiceProvider.setCurrentMode("local");
    }

    private static AiTool dualDescTool() {
        return new AiTool() {
            public String getName() { return "t"; }
            public String getDescription() { return "CLOUD-ONLY-MARKER"; }
            public String getLocalDescription() { return "LOCAL-ONLY-MARKER"; }
            public List<AiToolParam> getParameters() { return List.of(); }
            public List<AiToolParam> getLocalParameters() { return List.of(); }
            public AiToolResult execute(Map<String, Object> args) { return AiToolResult.success("ok"); }
        };
    }

    @Test
    void promptUsesLocalDescriptionInLocalMode() {
        AiServiceProvider.setCurrentMode("local");
        String md = ToolSchemaBuilder.buildPromptDefinitions(List.of(dualDescTool()));
        assertTrue(md.contains("LOCAL-ONLY-MARKER"));
        assertFalse(md.contains("CLOUD-ONLY-MARKER"));
    }

    @Test
    void promptUsesCloudDescriptionInCloudMode() {
        AiServiceProvider.setCurrentMode("openai");
        String md = ToolSchemaBuilder.buildPromptDefinitions(List.of(dualDescTool()));
        assertTrue(md.contains("CLOUD-ONLY-MARKER"));
        assertFalse(md.contains("LOCAL-ONLY-MARKER"));
    }
}
