package fan.summer.fengyu.ai.config;

import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.runtime.PluginProcessManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiToolRegistryBrowserSuppressionTest {

    @AfterEach
    void resetDesktopProp() {
        System.clearProperty("fengyu.desktop");
    }

    /** Builds a minimal {@link PluginManifest} whose aiTools list carries a browser_* tool name. */
    private static PluginManifest browserManifest(String id) {
        // PluginManifest is a 15-component record (see PluginManifest.java). AiTool has a 7-component
        // canonical constructor (name, description, inputSchema, outputSchema, method, timeoutSeconds, effect)
        // and Backend has (command, protocol, callTimeoutSeconds).
        var tool = new PluginManifest.AiTool(
                "browser_navigate", "nav", "{\"type\":\"object\"}", "{\"type\":\"object\"}",
                "browser_navigate", 60L, "external");
        // 15 components: schemaVersion,id,name,description,version,author,icon,category,
        //                ui,backend,permissions,homepage,official,aiTools,i18n
        return new PluginManifest(
                1, id, "Browser", "browser automation", "1.0.0", "test", null, "automation",
                null,
                new PluginManifest.Backend("cmd", "json-rpc-2.0", 60L),
                List.of("network"), null, true, List.of(tool), null);
    }

    @SuppressWarnings("unchecked")
    private static AiToolRegistry newRegistry(PluginPackageService pkg, PluginManifest manifest) {
        // Stubbing order matters: complete the isEnabled stub before starting the installed() stub,
        // otherwise Mockito reports UnfinishedStubbing when both run during one when(...) chain.
        when(pkg.isEnabled(manifest.id())).thenReturn(true);
        when(pkg.installed()).thenReturn(List.of(manifest));
        return new AiToolRegistry(List.of(), pkg, mock(PluginProcessManager.class),
                mock(ObjectProvider.class));
    }

    @Test
    void desktopModeSkipsFanSummerBrowserPlugin() {
        System.setProperty("fengyu.desktop", "true");
        var pkg = mock(PluginPackageService.class);
        var manifest = browserManifest("fan.summer.browser");

        var registry = newRegistry(pkg, manifest);
        List<ToolCallback> cbs = registry.callbacks();
        assertTrue(cbs.stream().noneMatch(cb -> cb.getToolDefinition().name().startsWith("browser_")),
                "fan.summer.browser tools must be suppressed in desktop mode");
    }

    @Test
    void nonDesktopModeRegistersPluginBrowserTools() {
        // desktop prop absent → false → plugin tools ARE registered (web mode, no host tool collision)
        var pkg = mock(PluginPackageService.class);
        var manifest = browserManifest("fan.summer.browser");

        var registry = newRegistry(pkg, manifest);
        List<ToolCallback> cbs = registry.callbacks();
        assertTrue(cbs.stream().anyMatch(cb -> cb.getToolDefinition().name().equals("browser_navigate")),
                "plugin browser tools must be registered in non-desktop mode");
    }
}
