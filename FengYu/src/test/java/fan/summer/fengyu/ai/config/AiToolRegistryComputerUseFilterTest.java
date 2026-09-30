package fan.summer.fengyu.ai.config;

import fan.summer.fengyu.ai.FengYuTool;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.runtime.PluginProcessManager;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * The Settings master switch ({@code computerUseEnabled}) hides the whole {@code computer_*}
 * builtin family from both the live callback snapshot and the UI descriptors — mirroring how
 * plugin enable/disable is re-evaluated per snapshot.
 */
class AiToolRegistryComputerUseFilterTest {

    /** One builtin bean carrying a computer_* pair plus an unrelated control tool. */
    static final class FixtureTools implements FengYuTool {
        @Tool(name = "computer_screenshot", description = "capture")
        public String screenshot() { return "{}"; }

        @Tool(name = "computer_click", description = "click")
        public String click() { return "{}"; }

        @Tool(name = "web_fetch", description = "fetch")
        public String fetch() { return "{}"; }
    }

    private AiToolRegistry registry(boolean computerUseEnabled) {
        return new AiToolRegistry(List.of(new FixtureTools()),
                mock(PluginPackageService.class), mock(PluginProcessManager.class),
                mock(ObjectProvider.class), null, null, null,
                () -> computerUseEnabled);
    }

    @Test
    void switchOffHidesComputerToolsFromCallbacksAndDescriptors() {
        AiToolRegistry registry = registry(false);
        List<ToolCallback> callbacks = registry.callbacks();
        assertTrue(callbacks.stream().noneMatch(cb -> cb.getToolDefinition().name().startsWith("computer_")),
                "computer_* must be hidden while the switch is off");
        assertTrue(callbacks.stream().anyMatch(cb -> cb.getToolDefinition().name().equals("web_fetch")),
                "unrelated tools stay visible");
        assertTrue(registry.descriptors("en").stream().noneMatch(d -> d.name().startsWith("computer_")));
    }

    @Test
    void switchOnKeepsComputerTools() {
        AiToolRegistry registry = registry(true);
        assertTrue(registry.callbacks().stream()
                .anyMatch(cb -> cb.getToolDefinition().name().equals("computer_screenshot")));
        assertTrue(registry.descriptors("en").stream()
                .anyMatch(d -> d.name().equals("computer_click")));
    }
}
