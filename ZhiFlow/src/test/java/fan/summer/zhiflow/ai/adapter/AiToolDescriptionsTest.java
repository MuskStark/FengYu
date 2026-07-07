package fan.summer.zhiflow.ai.adapter;

import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.ai.AiToolParam;
import fan.summer.zhiflow.api.ai.AiToolResult;
import fan.summer.zhiflow.api.ai.AiServiceProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiToolDescriptionsTest {

    private static AiTool tool(String cloudDesc, String localDesc,
                                List<AiToolParam> cloudParams,
                                List<AiToolParam> localParams) {
        return new AiTool() {
            public String getName() { return "t"; }
            public String getDescription() { return cloudDesc; }
            public String getLocalDescription() { return localDesc; }
            public List<AiToolParam> getParameters() { return cloudParams; }
            public List<AiToolParam> getLocalParameters() { return localParams; }
            public AiToolResult execute(Map<String, Object> args) { return AiToolResult.success("ok"); }
        };
    }

    @AfterEach
    void reset() {
        AiServiceProvider.setCurrentMode("local");
    }

    @Test
    void picksLocalDescriptionInLocalMode() {
        AiServiceProvider.setCurrentMode("local");
        AiTool t = tool("cloud", "local", List.of(), List.of());
        assertEquals("local", AiToolDescriptions.pickDescription(t));
    }

    @Test
    void picksCloudDescriptionInCloudMode() {
        AiServiceProvider.setCurrentMode("openai");
        AiTool t = tool("cloud", "local", List.of(), List.of());
        assertEquals("cloud", AiToolDescriptions.pickDescription(t));
    }

    @Test
    void picksLocalParametersInLocalMode() {
        AiServiceProvider.setCurrentMode("local");
        AiToolParam cloud = AiToolParam.of("a", "string", "cloud-only");
        AiToolParam local = AiToolParam.of("b", "string", "local-only");
        AiTool t = tool("c", "l", List.of(cloud), List.of(local));
        assertEquals(List.of(local), AiToolDescriptions.pickParameters(t));
    }

    @Test
    void picksCloudParametersInCloudMode() {
        AiServiceProvider.setCurrentMode("anthropic");
        AiToolParam cloud = AiToolParam.of("a", "string", "cloud-only");
        AiToolParam local = AiToolParam.of("b", "string", "local-only");
        AiTool t = tool("c", "l", List.of(cloud), List.of(local));
        assertEquals(List.of(cloud), AiToolDescriptions.pickParameters(t));
    }
}
