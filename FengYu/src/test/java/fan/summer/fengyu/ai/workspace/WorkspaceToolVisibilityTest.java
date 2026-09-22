package fan.summer.fengyu.ai.workspace;

import fan.summer.fengyu.ai.FengYuTool;
import fan.summer.fengyu.ai.config.AiToolRegistry;
import fan.summer.fengyu.ai.tools.WorkspaceFileTools;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.runtime.PluginProcessManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The coding file tools exist in the builtin catalog but are hidden from every snapshot unless
 * the turn carries a workspace binding — mirroring the computer-use master-switch filter. An
 * unbound conversation (including agent runs and flow chats) must never be offered a tool whose
 * path jail would reject every call.
 */
class WorkspaceToolVisibilityTest {

    @TempDir
    Path root;

    private AiToolRegistry registry() {
        PluginPackageService packages = new PluginPackageService(root.toString());
        return new AiToolRegistry(
                List.of((FengYuTool) new WorkspaceFileTools(new WorkspaceReadState())),
                packages, mock(PluginProcessManager.class), null);
    }

    private static List<String> names(AiToolRegistry registry) {
        return registry.callbacks().stream()
                .map(ToolCallback::getToolDefinition)
                .map(definition -> definition.name())
                .toList();
    }

    @AfterEach
    void unbind() {
        WorkspaceContext.clear();
    }

    @Test
    void hiddenWithoutWorkspaceBinding() {
        List<String> names = names(registry());
        assertFalse(names.contains("read_file"));
        assertFalse(names.contains("edit_file"));
        assertFalse(names.contains("grep"));
        assertFalse(names.contains("glob"));
        assertFalse(names.contains("write_file"));
    }

    @Test
    void visibleWithWorkspaceBinding() {
        WorkspaceContext.set(new WorkspaceContext.Binding(root, 1L));
        List<String> names = names(registry());
        assertTrue(names.contains("read_file"));
        assertTrue(names.contains("write_file"));
        assertTrue(names.contains("edit_file"));
        assertTrue(names.contains("grep"));
        assertTrue(names.contains("glob"));
    }
}
