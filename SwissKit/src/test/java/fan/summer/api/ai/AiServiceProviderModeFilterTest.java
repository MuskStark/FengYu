package fan.summer.api.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiServiceProviderModeFilterTest {

    private static AiTool tool(String name, boolean local, boolean cloud) {
        return new AiTool() {
            public String getName() { return name; }
            public String getDescription() { return name; }
            public List<AiToolParam> getParameters() { return List.of(); }
            public boolean supportsLocal() { return local; }
            public boolean supportsCloud() { return cloud; }
            public AiToolResult execute(Map<String, Object> args) { return AiToolResult.success("ok"); }
        };
    }

    @BeforeEach
    void reset() {
        AiServiceProvider.clearTools();
        AiServiceProvider.clearConstrainedTool();
        AiServiceProvider.setCurrentMode("local");
    }

    @AfterEach
    void cleanup() {
        AiServiceProvider.clearTools();
        AiServiceProvider.clearConstrainedTool();
        AiServiceProvider.setCurrentMode("local");
    }

    @Test
    void localModeHidesCloudOnlyTool() {
        AiServiceProvider.registerTool(tool("both", true, true));
        AiServiceProvider.registerTool(tool("cloudOnly", false, true));

        AiServiceProvider.setCurrentMode("local");
        List<String> names = AiServiceProvider.getTools().stream().map(AiTool::getName).toList();
        assertEquals(List.of("both"), names);
    }

    @Test
    void cloudModeHidesLocalOnlyTool() {
        AiServiceProvider.registerTool(tool("both", true, true));
        AiServiceProvider.registerTool(tool("localOnly", true, false));

        AiServiceProvider.setCurrentMode("openai");
        List<String> names = AiServiceProvider.getTools().stream().map(AiTool::getName).toList();
        assertEquals(List.of("both"), names);
    }

    @Test
    void modeSwitchChangesVisibilityImmediately() {
        AiServiceProvider.registerTool(tool("cloudOnly", false, true));
        AiServiceProvider.setCurrentMode("local");
        assertTrue(AiServiceProvider.getTools().isEmpty());

        AiServiceProvider.setCurrentMode("anthropic");
        assertEquals(1, AiServiceProvider.getTools().size());
    }

    @Test
    void constrainedToolStillFilteredByMode() {
        AiServiceProvider.registerTool(tool("cloudOnly", false, true));
        AiServiceProvider.setCurrentMode("local");
        AiServiceProvider.setConstrainedTool("cloudOnly");
        assertTrue(AiServiceProvider.getTools().isEmpty(),
                "Constrained filter should not bypass mode filter");
    }
}
