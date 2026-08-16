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
        // v2: AiTool has (name, description, method, timeoutSeconds, effect); Backend has only
        // callTimeoutSeconds. The input schema lives on rpc.methods, referenced by the tool's method.
        var tool = new PluginManifest.AiTool(
                "browser_navigate", "nav", "browser_navigate", 60L, "external");
        return new PluginManifest(
                2, id, "Browser", "browser automation", "1.0.0", "test", null, "automation",
                null,
                new PluginManifest.Backend(60L),
                List.of("network"), null, true, null, List.of(tool), null, null);
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
