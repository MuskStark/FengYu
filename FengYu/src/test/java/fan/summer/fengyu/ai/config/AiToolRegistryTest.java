package fan.summer.fengyu.ai.config;

import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.runtime.PluginProcessManager;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService;
import fan.summer.fengyu.ai.ChatFileContext;
import org.junit.jupiter.api.AfterEach;
import fan.summer.fengyu.ai.tools.AuditedToolCallback;
import fan.summer.fengyu.ai.tools.ToolEffect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.*;

class AiToolRegistryTest {

    @TempDir Path temp;

    @AfterEach
    void clearFileContext() {
        ChatFileContext.clear();
    }

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
        assertEquals(ToolEffect.EXTERNAL,
                ((AuditedToolCallback) registry.callbacks().getFirst()).effect());

        packages.setEnabled("com.example.live", false);
        assertTrue(registry.descriptors().isEmpty());
        packages.setEnabled("com.example.live", true);
        assertEquals(1, registry.callbacks().size());
        packages.uninstall("com.example.live");
        assertTrue(registry.callbacks().isEmpty());
    }

    @Test
    void writeDirectoryToolUsesStagingGrantWithoutRestartingWorker() throws Exception {
        Path plugin = Files.createDirectories(temp.resolve("fan.summer.excel"));
        Files.writeString(plugin.resolve("manifest.json"), """
            {"schemaVersion":1,"id":"fan.summer.excel","name":"Excel","description":"test",
             "version":"1.0.0","author":"test","icon":"table","category":"dev",
             "ui":{"entry":"ui/index.html"},
             "backend":{"command":"java -jar worker.jar","protocol":"json-rpc-2.0"},
             "permissions":["files.read","files.write"],"aiTools":[{
               "name":"excel_execute","description":"Execute split","method":"execute",
               "inputSchema":"{\\"type\\":\\"object\\",\\"properties\\":{\\"outputDir\\":{\\"type\\":\\"object\\",\\"description\\":\\"A writable FengYu DirectoryRef\\"}}}"
             }]}
            """);
        PluginPackageService packages = new PluginPackageService(temp.toString());
        PluginFileGrantService files = new PluginFileGrantService(temp.resolve("runtime").toString());
        // A staging grant (access="write") is already in the turn's context — the worker picks it
        // up directly. No per-call promotion or process restart happens.
        var staging = files.outputDirectory("fan.summer.excel");
        ChatFileContext.set(List.of(new ChatFileContext.ActiveFileRef("fan.summer.excel", staging)));
        PluginProcessManager processes = mock(PluginProcessManager.class);
        when(processes.invoke(eq("fan.summer.excel"), eq("execute"), anyMap(), eq(-1L)))
            .thenAnswer(invocation -> {
                @SuppressWarnings("unchecked") Map<String, Object> params = invocation.getArgument(2);
                @SuppressWarnings("unchecked") Map<String, Object> ref = (Map<String, Object>) params.get("outputDir");
                assertEquals("write", ref.get("access"));
                return Map.of("success", true);
            });
        @SuppressWarnings("unchecked")
        ObjectProvider<SyncMcpToolCallbackProvider> mcp = mock(ObjectProvider.class);
        AiToolRegistry registry = new AiToolRegistry(List.of(), packages, processes, mcp);

        registry.callbacks().getFirst().call("{}");

        // The worker is NOT stopped after the call — staging lives for the whole turn.
        verify(processes, never()).stop("fan.summer.excel");
    }
}
