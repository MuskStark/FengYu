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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                mock(PluginProcessManager.class), mock(PluginLogStore.class),
                new fan.summer.fengyu.web.StreamTicketService());

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

    /**
     * P3 cross-site guard for the token-exempt asset endpoint: same-origin iframe navigations and
     * opaque-origin sandboxed-iframe subresource loads pass; a foreign site embedding or probing
     * the loopback host (cross-site document/iframe destinations, or an explicit foreign Origin on
     * a header-less client) is refused.
     */
    @Test
    void acceptableFetchSiteAllowsSameOriginAndBlocksCrossSiteDocuments() {
        // Same-origin / same-site / none (typed address bar) always pass.
        assertTrue(fetchSiteAllowed("same-origin", "iframe"));
        assertTrue(fetchSiteAllowed("same-site", "document"));
        assertTrue(fetchSiteAllowed("none", "document"));

        // Cross-site subresources pass: the shell's sandboxed plugin iframes run in an OPAQUE
        // origin, so their script/style/frame loads are legitimately labelled cross-site.
        assertTrue(fetchSiteAllowed("cross-site", "script"));
        assertTrue(fetchSiteAllowed("cross-site", "style"));
        assertTrue(fetchSiteAllowed("cross-site", "empty"));

        // Cross-site document-ish destinations (a foreign site embedding the loopback URL) fail.
        assertFalse(fetchSiteAllowed("cross-site", "document"));
        assertFalse(fetchSiteAllowed("cross-site", "iframe"));
        assertFalse(fetchSiteAllowed("cross-site", "object"));
        // Unknown destination on a cross-site request fails closed.
        assertFalse(fetchSiteAllowed("cross-site", null));
        assertFalse(fetchSiteAllowed("cross-site", ""));

        // Header-less clients (curl, older webviews) pass.
        assertTrue(fetchSiteAllowed(null, null));

        // Without Sec-Fetch-Site, an explicit Origin is the fallback signal: loopback (or none)
        // passes, a foreign origin is refused, and a malformed origin fails closed.
        assertTrue(originAllowed(null));
        assertTrue(originAllowed("null")); // sandboxed iframe initiations send Origin: null
        assertTrue(originAllowed("http://127.0.0.1:24056"));
        assertTrue(originAllowed("http://localhost:24056"));
        assertTrue(originAllowed("http://[::1]:24056"));
        assertFalse(originAllowed("https://evil.example"));
        assertFalse(originAllowed("not a uri"));
    }

    private static boolean fetchSiteAllowed(String site, String dest) {
        var request = new MockHttpServletRequest("GET", "/plugin-runtime/x/ui/index.html");
        if (site != null) request.addHeader("Sec-Fetch-Site", site);
        if (dest != null) request.addHeader("Sec-Fetch-Dest", dest);
        return PluginRuntimeController.acceptableFetchSite(request);
    }

    private static boolean originAllowed(String origin) {
        var request = new MockHttpServletRequest("GET", "/plugin-runtime/x/ui/index.html");
        if (origin != null) request.addHeader("Origin", origin);
        return PluginRuntimeController.acceptableFetchSite(request);
    }
}
