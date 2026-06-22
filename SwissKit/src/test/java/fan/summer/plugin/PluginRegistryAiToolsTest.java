package fan.summer.plugin;

import fan.summer.api.SwissKitJPlugin;
import fan.summer.api.ToolCategory;
import fan.summer.api.ToolType;
import fan.summer.api.ai.AiServiceProvider;
import fan.summer.api.ai.AiTool;
import fan.summer.api.ai.AiToolParam;
import fan.summer.api.ai.AiToolResult;
import javafx.scene.Node;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PluginRegistryAiToolsTest {

    private PluginRegistry registry;
    private PluginLoader loader;

    private static AiTool tool(String name) {
        return new AiTool() {
            public String getName() { return name; }
            public String getDescription() { return name; }
            public List<AiToolParam> getParameters() { return List.of(); }
            public AiToolResult execute(Map<String, Object> args) { return AiToolResult.success("ok"); }
        };
    }

    private static SwissKitJPlugin plugin(String id, List<AiTool> tools) {
        return new SwissKitJPlugin() {
            public String getId() { return id; }
            public String getName() { return id; }
            public String getDescription() { return ""; }
            public ToolCategory getCategory() { return ToolCategory.OTHER; }
            public String getVersion() { return "0.0.1"; }
            public String getMdiIcon() { return "circle"; }
            public Node createView() { return null; }
            public ToolType getType() { return ToolType.PLUGIN; }
            public List<AiTool> aiTools() { return tools; }
        };
    }

    @BeforeEach
    void setup() {
        AiServiceProvider.clearTools();
        loader = new PluginLoader(null);
        registry = new PluginRegistry(loader);
        PluginRegistry.setInstanceForTest(registry);
    }

    @AfterEach
    void teardown() {
        AiServiceProvider.clearTools();
        PluginRegistry.setInstanceForTest(null);
    }

    @Test
    void addPluginsRegistersAiTools() {
        SwissKitJPlugin p = plugin("p1", List.of(tool("t1"), tool("t2")));
        registry.addPlugins(List.of(p));

        assertNotNull(AiServiceProvider.getTool("t1"));
        assertNotNull(AiServiceProvider.getTool("t2"));
    }

    @Test
    void removePluginUnregistersAiTools() {
        SwissKitJPlugin p = plugin("p1", List.of(tool("t1")));
        registry.addPlugins(List.of(p));
        assertNotNull(AiServiceProvider.getTool("t1"));

        registry.removePlugin(p);
        assertNull(AiServiceProvider.getTool("t1"));
    }

    @Test
    void pluginWithEmptyAiToolsIsSafe() {
        SwissKitJPlugin p = plugin("p1", List.of());
        registry.addPlugins(List.of(p));
        assertTrue(AiServiceProvider.getTools().isEmpty());
    }

    @Test
    void pluginThrowingInAiToolsDoesNotCrash() {
        SwissKitJPlugin bad = new SwissKitJPlugin() {
            public String getId() { return "bad"; }
            public String getName() { return "bad"; }
            public String getDescription() { return ""; }
            public ToolCategory getCategory() { return ToolCategory.OTHER; }
            public String getVersion() { return "0.0.1"; }
            public String getMdiIcon() { return "circle"; }
            public Node createView() { return null; }
            public List<AiTool> aiTools() { throw new RuntimeException("oops"); }
        };
        assertDoesNotThrow(() -> registry.addPlugins(List.of(bad)));
        assertTrue(AiServiceProvider.getTools().isEmpty());
    }
}
