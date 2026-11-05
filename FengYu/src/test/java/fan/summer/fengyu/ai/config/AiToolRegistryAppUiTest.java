package fan.summer.fengyu.ai.config;

import fan.summer.fengyu.ai.AiToolResult;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.runtime.PluginProcessManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plugin app-card surface: the {@code aiTools[].app} declaration must reach both consumers —
 * the descriptor catalog ({@code appUi} JSON for the flow builder) and the additive chat ui
 * projection ({@link PluginUiRuntimeHolder}) — without touching the model-visible result.
 */
class AiToolRegistryAppUiTest {

    @TempDir Path temp;

    @AfterEach
    void clearHolder() {
        PluginUiRuntimeHolder.clear();
    }

    private AiToolRegistry registryWith(String manifestJson) throws Exception {
        return registryWith(manifestJson, List.of());
    }

    private AiToolRegistry registryWith(String manifestJson,
            List<fan.summer.fengyu.ai.FengYuTool> builtins) throws Exception {
        Path plugin = Files.createDirectories(temp.resolve("com.example.app"));
        Files.writeString(plugin.resolve("manifest.json"), manifestJson);
        PluginPackageService packages = new PluginPackageService(temp.toString());
        @SuppressWarnings("unchecked")
        ObjectProvider<SyncMcpToolCallbackProvider> mcp = Mockito.mock(ObjectProvider.class);
        PluginProcessManager processes = Mockito.mock(PluginProcessManager.class);
        return new AiToolRegistry(builtins, packages, processes, mcp);
    }

    @Test
    void descriptorsCarryAppUiJsonWithLocalizedTitle() throws Exception {
        AiToolRegistry registry = registryWith("""
            {"schemaVersion":2,"id":"com.example.app","name":"App","description":"test",
             "version":"1.0.0","author":"test","icon":"toolbox","category":"dev",
             "ui":{"entry":"ui/index.html"},
             "rpc":{"methods":{"echo":{"inputSchema":{"type":"object","properties":{}}}}},
             "aiTools":[{"name":"app_echo","description":"Echo","method":"echo","effect":"read",
               "app":{"path":"ui/tool.html","title":"Result view"}}],
             "i18n":{"zh":{"aiTools":{"app_echo":{"appTitle":"结果视图"}}}}}
            """);
        var descriptor = registry.descriptors("zh").getFirst();
        String appUi = descriptor.appUi();
        assertTrue(appUi != null && appUi.contains("\"kind\":\"plugin\""), appUi);
        assertTrue(appUi.contains("\"pluginId\":\"com.example.app\""), appUi);
        assertTrue(appUi.contains("\"path\":\"ui/tool.html\""), appUi);
        assertTrue(appUi.contains("\"mode\":\"card\""), "mode defaults to card");
        assertTrue(appUi.contains("\"title\":\"结果视图\""), "i18n appTitle wins: " + appUi);
    }

    @Test
    void toolsWithoutAppDeclareNoAppUi() throws Exception {
        AiToolRegistry registry = registryWith("""
            {"schemaVersion":2,"id":"com.example.app","name":"App","description":"test",
             "version":"1.0.0","author":"test","icon":"toolbox","category":"dev",
             "ui":{"entry":"ui/index.html"},
             "rpc":{"methods":{"echo":{"inputSchema":{"type":"object","properties":{}}}}},
             "aiTools":[{"name":"plain_echo","description":"Echo","method":"echo","effect":"read"}]}
            """);
        assertNull(registry.descriptors("en").getFirst().appUi());
        assertNull(registry.pluginUiDescriptor("plain_echo"));
    }

    @Test
    void pluginUiDescriptorFollowsTheExecutorWinnerSemantics() throws Exception {
        // Plugin A (first in installed() order) declares the name WITHOUT an app; plugin B
        // declares the same name WITH one. The executor resolves the name to A, so the card
        // must be none — never B's view on A's result.
        AiToolRegistry registry = registryWith("""
            {"schemaVersion":2,"id":"com.example.first","name":"First","description":"test",
             "version":"1.0.0","author":"test","icon":"toolbox","category":"dev",
             "ui":{"entry":"ui/index.html"},
             "rpc":{"methods":{"echo":{"inputSchema":{"type":"object","properties":{}}}}},
             "aiTools":[{"name":"shared_name","description":"A","method":"echo","effect":"read"}]}
            """);
        Files.createDirectories(temp.resolve("com.example.second"));
        Files.writeString(temp.resolve("com.example.second/manifest.json"), """
            {"schemaVersion":2,"id":"com.example.second","name":"Second","description":"test",
             "version":"1.0.0","author":"test","icon":"toolbox","category":"dev",
             "ui":{"entry":"ui/index.html"},
             "rpc":{"methods":{"echo":{"inputSchema":{"type":"object","properties":{}}}}},
             "aiTools":[{"name":"shared_name","description":"B","method":"echo","effect":"read",
               "app":{"path":"ui/b.html"}}]}
            """);
        assertNull(registry.pluginUiDescriptor("shared_name"),
                "the first declarer owns the wire name — no cross-plugin card");
    }

    @Test
    void builtinToolNamesNeverWearPluginCards() throws Exception {
        AiToolRegistry registry = registryWith("""
            {"schemaVersion":2,"id":"com.example.shadow","name":"Shadow","description":"test",
             "version":"1.0.0","author":"test","icon":"toolbox","category":"dev",
             "ui":{"entry":"ui/index.html"},
             "rpc":{"methods":{"echo":{"inputSchema":{"type":"object","properties":{}}}}},
             "aiTools":[{"name":"grep","description":"A plugin named like a host tool",
               "method":"echo","effect":"read","app":{"path":"ui/tool.html"}}]}
            """, List.of(new GrepBuiltin()));
        assertNull(registry.pluginUiDescriptor("grep"),
                "a plugin tool shadowed by a host builtin never executes — no card either");
    }

    /** A host builtin named like the shadowing plugin tool (trust order: host wins the name). */
    static final class GrepBuiltin implements fan.summer.fengyu.ai.FengYuTool {
        @org.springframework.ai.tool.annotation.Tool(name = "grep", description = "Host grep")
        public String run(String pattern) { return "{}"; }
    }

    @Test
    void holderAttachesChatUiWithoutTouchingTheModelResult() throws Exception {
        AiToolRegistry registry = registryWith("""
            {"schemaVersion":2,"id":"com.example.app","name":"App","description":"test",
             "version":"1.0.0","author":"test","icon":"toolbox","category":"dev",
             "ui":{"entry":"ui/index.html"},
             "rpc":{"methods":{"echo":{"inputSchema":{"type":"object","properties":{}}}}},
             "aiTools":[{"name":"app_echo","description":"Echo","method":"echo","effect":"read",
               "app":{"path":"ui/tool.html","mode":"dialog","title":"Review"}}]}
            """);
        PluginUiRuntimeHolder.register(registry::pluginUiDescriptor);

        AiToolResult plain = PluginUiRuntimeHolder.attach("app_echo",
                new AiToolResult(true, "the model keeps this text", null));
        assertEquals("the model keeps this text", plain.output());
        Map<String, Object> ui = plain.ui();
        assertEquals("plugin", ui.get("kind"));
        assertEquals("com.example.app", ui.get("pluginId"));
        assertEquals("ui/tool.html", ui.get("path"));
        assertEquals("dialog", ui.get("mode"));
        assertEquals("Review", ui.get("title"));

        // Unknown tools and already-projected results pass through untouched.
        assertNull(PluginUiRuntimeHolder.uiFor("not_a_tool"));
        AiToolResult projected = new AiToolResult(true, "text", Map.of("kind", "mcp"));
        assertEquals(projected, PluginUiRuntimeHolder.attach("app_echo", projected));

        // Disabled plugins stop projecting; the descriptor lookup follows enable state.
        new PluginPackageService(temp.toString()).setEnabled("com.example.app", false);
        assertNull(PluginUiRuntimeHolder.uiFor("app_echo"));
    }
}
