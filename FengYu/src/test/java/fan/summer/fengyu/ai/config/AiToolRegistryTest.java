package fan.summer.fengyu.ai.config;

import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.runtime.PluginProcessManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AiToolRegistryTest {

    @TempDir Path temp;

    @Test
    void pluginCatalogFollowsEnableAndUninstallWithoutRecreatingRegistry() throws Exception {
        Path plugin = temp.resolve("com.example.live");
        Files.createDirectories(plugin);
        Files.writeString(plugin.resolve("manifest.json"), """
            {"schemaVersion":1,"id":"com.example.live","name":"Live","description":"Live tools",
             "version":"1.0.0","author":"Example","icon":"toolbox","category":"dev",
             "ui":{"entry":"ui/index.html"},"aiTools":[{
               "name":"live_echo","description":"Echo","method":"echo",
               "inputSchema":"{\\\"type\\\":\\\"object\\\",\\\"properties\\\":{}}",
               "outputSchema":"{\\\"type\\\":\\\"object\\\",\\\"properties\\\":{\\\"text\\\":{\\\"type\\\":\\\"string\\\"}}}"
             }]}
            """);
        JsonMapper.builder().findAndAddModules().build()
                .readValue(plugin.resolve("manifest.json").toFile(),
                        fan.summer.fengyu.plugin.market.PluginManifest.class);

        PluginPackageService packages = new PluginPackageService(temp.toString());
        @SuppressWarnings("unchecked")
        ObjectProvider<SyncMcpToolCallbackProvider> mcp = mock(ObjectProvider.class);
        AiToolRegistry registry = new AiToolRegistry(List.of(), packages,
                mock(PluginProcessManager.class), mcp);

        var descriptor = registry.descriptors().getFirst();
        assertEquals("com.example.live:live_echo", descriptor.id());
        assertTrue(descriptor.outputSchema().contains("text"));

        packages.setEnabled("com.example.live", false);
        assertTrue(registry.descriptors().isEmpty());
        packages.setEnabled("com.example.live", true);
        assertEquals(1, registry.callbacks().size());
        packages.uninstall("com.example.live");
        assertTrue(registry.callbacks().isEmpty());
    }
}
