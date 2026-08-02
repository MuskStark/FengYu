package fan.summer.fengyu.ai;

import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatFileGrantServiceTest {
    @TempDir Path temp;

    @Test
    void extractsQuotedAndUnquotedExistingAbsolutePaths() throws Exception {
        Path spaced = Files.writeString(temp.resolve("sales report.xlsx"), "data").toRealPath();
        Path folder = Files.createDirectories(temp.resolve("project folder")).toRealPath();

        List<Path> paths = ChatFileGrantService.extractExistingPaths(
            "分析 \"" + spaced + "\"，然后检查 " + folder + " 里的内容");

        assertEquals(List.of(spaced, folder), paths);
    }

    @Test
    void fansSelectionOutOnlyToEnabledFileCapableBackendPlugins() throws Exception {
        Path pluginRoot = Files.createDirectories(temp.resolve("plugins"));
        installManifest(pluginRoot, "test.reader", List.of("files.read"), true);
        installManifest(pluginRoot, "test.writer", List.of("files.read", "files.write"), true);
        installManifest(pluginRoot, "test.ui-only", List.of("files.read"), false);
        installManifest(pluginRoot, "test.no-files", List.of(), true);

        PluginFileGrantService files = new PluginFileGrantService(temp.resolve("grants").toString());
        ChatFileGrantService service = new ChatFileGrantService(
            new PluginPackageService(pluginRoot.toString()), files);
        Path selected = Files.createDirectories(temp.resolve("selected"));
        Files.writeString(selected.resolve("input.txt"), "hello");

        var refs = service.grantNative(selected.toString(), "directory", true);

        assertEquals(List.of("test.reader", "test.writer"),
            refs.stream().map(ChatFileContext.ActiveFileRef::pluginId).sorted().toList());
        var reader = refs.stream().filter(ref -> ref.pluginId().equals("test.reader")).findFirst().orElseThrow();
        var writer = refs.stream().filter(ref -> ref.pluginId().equals("test.writer")).findFirst().orElseThrow();
        assertEquals("read", reader.ref().access());
        assertEquals("read-write", writer.ref().access());
        assertTrue(Files.exists(files.resolve("test.reader", reader.ref().id()).resolve("input.txt")));
        assertEquals(selected.toRealPath(), files.resolve("test.writer", writer.ref().id()));
    }

    @Test
    void typedDirectoryPathsRemainReadOnly() throws Exception {
        Path pluginRoot = Files.createDirectories(temp.resolve("typed-plugins"));
        installManifest(pluginRoot, "test.readwrite", List.of("files.read", "files.write"), true);
        ChatFileGrantService service = new ChatFileGrantService(
            new PluginPackageService(pluginRoot.toString()),
            new PluginFileGrantService(temp.resolve("typed-grants").toString()));
        Path selected = Files.createDirectories(temp.resolve("typed project"));

        var refs = service.grantPathsFromUserText("请读取 `" + selected + "`");

        assertEquals(1, refs.size());
        assertEquals("read", refs.getFirst().ref().access());
    }

    private static void installManifest(Path root, String id, List<String> permissions,
            boolean backend) throws Exception {
        Path directory = Files.createDirectories(root.resolve(id));
        String backendJson = backend
            ? "{\"command\":\"java -jar worker.jar\",\"protocol\":\"json-rpc-2.0\"}"
            : "null";
        String permissionJson = permissions.stream().map(value -> "\"" + value + "\"")
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        Files.writeString(directory.resolve("manifest.json"), """
            {"schemaVersion":1,"id":"%s","name":"%s","description":"test","version":"1.0.0",
             "author":"test","icon":"test","category":"OTHER","ui":{"entry":"ui/index.html"},
             "backend":%s,"permissions":%s,"official":false,"aiTools":[]}
            """.formatted(id, id, backendJson, permissionJson));
    }
}
