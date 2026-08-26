package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.runtime.PluginLogStore;
import fan.summer.fengyu.plugin.runtime.PluginProcessManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class PluginRuntimeControllerTest {

    @Test
    void pluginCspAllowsBundledDataFontsAndSameOriginFontAssets() {
        assertTrue(PluginRuntimeController.PLUGIN_CONTENT_SECURITY_POLICY
                .contains("font-src 'self' data:"));
    }

    @Test
    void textAssetsUseUtf8Charset() {
        assertAll(
                () -> assertEquals(StandardCharsets.UTF_8,
                        PluginRuntimeController.contentType("index.html").getCharset()),
                () -> assertEquals(StandardCharsets.UTF_8,
                        PluginRuntimeController.contentType("app.js").getCharset()),
                () -> assertEquals(StandardCharsets.UTF_8,
                        PluginRuntimeController.contentType("app.css").getCharset()),
                () -> assertEquals(StandardCharsets.UTF_8,
                        PluginRuntimeController.contentType("messages.json").getCharset())
        );
    }

    /**
     * The token-exempt asset endpoint serves ONLY the UI subtree: worker.jar, the manifest, and
     * every other packaged file must not be downloadable without the launch token (M-5).
     */
    @Test
    void tokenExemptAssetsAreLimitedToTheUiSubtree(@TempDir Path pluginsRoot) throws Exception {
        String pluginId = "test.assetplugin";
        Path dir = Files.createDirectories(pluginsRoot.resolve(pluginId));
        Files.writeString(dir.resolve("manifest.json"), """
            {"schemaVersion":2,"id":"%s","name":"A","description":"t","version":"1.0.0",
             "author":"t","icon":"t","category":"OTHER","ui":{"entry":"ui/index.html"},
             "backend":{"callTimeoutSeconds":60},"permissions":[],"official":false,"aiTools":[]}
            """.formatted(pluginId));
        Files.createDirectories(dir.resolve("ui"));
        Files.writeString(dir.resolve("ui/index.html"), "<!doctype html><title>ui</title>");
        Files.write(dir.resolve("worker.jar"), new byte[] { 1, 2, 3 });
        PluginRuntimeController controller = new PluginRuntimeController(
                new PluginPackageService(pluginsRoot.toString()),
                mock(PluginProcessManager.class), mock(PluginLogStore.class));

        assertEquals(200, controller.asset(pluginId, requestForAsset(pluginId, "ui/index.html"))
                .getStatusCode().value(), "the iframe entry itself stays reachable");
        assertEquals(404, controller.asset(pluginId, requestForAsset(pluginId, "worker.jar"))
                .getStatusCode().value(), "the worker binary must not be token-exempt");
        assertEquals(404, controller.asset(pluginId, requestForAsset(pluginId, "manifest.json"))
                .getStatusCode().value(), "the manifest must not be token-exempt");
        // Traversal through the prefix check must not resurrect whole-directory access either.
        assertEquals(404, controller.asset(pluginId, requestForAsset(pluginId, "ui/../worker.jar"))
                .getStatusCode().value(), "a ui/.. hop must not reach the package root");
        // A bare directory URL falls back to the declared entry — inside the UI subtree.
        assertEquals(200, controller.asset(pluginId, requestForAsset(pluginId, ""))
                .getStatusCode().value(), "the entry fallback stays reachable");
    }

    private static MockHttpServletRequest requestForAsset(String pluginId, String relative) {
        var request = new MockHttpServletRequest("GET", "/plugin-runtime/" + pluginId + "/" + relative);
        request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE,
                "/plugin-runtime/" + pluginId + "/" + relative);
        return request;
    }
}
