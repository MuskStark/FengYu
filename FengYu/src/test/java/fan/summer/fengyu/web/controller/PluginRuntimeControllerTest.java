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
        // Real packaged Electron request: app://shell -> HTTP loopback is cross-site.
        var navigation = requestForAsset(pluginId, "ui/index.html");
        navigation.addHeader("Sec-Fetch-Site", "cross-site");
        navigation.addHeader("Sec-Fetch-Dest", "iframe");
        navigation.setParameter("shellOrigin", "app://shell");
        assertEquals(403, controller.asset(pluginId, navigation).getStatusCode().value(),
                "a forged shellOrigin must not authorize an embedding website");
        String ticket = controller.uiTicket(pluginId).getBody().ticket();
        navigation.setParameter("uiTicket", ticket);
        var loaded = controller.asset(pluginId, navigation);
        assertEquals(200, loaded.getStatusCode().value());
        assertEquals("no-store", loaded.getHeaders().getFirst("Cache-Control"));
        assertEquals("no-referrer", loaded.getHeaders().getFirst("Referrer-Policy"));
        assertEquals(403, controller.asset(pluginId, navigation).getStatusCode().value(),
                "navigation tickets cannot be replayed");
        var wrongPath = requestForAsset(pluginId, "worker.jar");
        wrongPath.setParameter("uiTicket", controller.uiTicket(pluginId).getBody().ticket());
        assertEquals(403, controller.asset(pluginId, wrongPath).getStatusCode().value(),
                "entry tickets cannot authorize other package paths");
        assertEquals(404, controller.uiTicket("missing.plugin").getStatusCode().value());

        // The minting endpoint stays behind the launch-token filter, unlike UI asset GETs.
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new fan.summer.fengyu.web.filter.TokenAuthFilter(
                        new fan.summer.fengyu.web.StreamTicketService())).build();
        String property = fan.summer.fengyu.HeadlessLauncher.TOKEN_PROPERTY;
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, "test-launch-token");
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/plugin-runtime/" + pluginId + "/ui-ticket")
                    .header("Host", "127.0.0.1:24056"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/plugin-runtime/" + pluginId + "/ui-ticket")
                    .header("Host", "127.0.0.1:24056")
                    .header("X-FengYu-Token", "test-launch-token"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
        // Traversal through the prefix check must not resurrect whole-directory access either.
        assertEquals(404, controller.asset(pluginId, requestForAsset(pluginId, "ui/../worker.jar"))
                .getStatusCode().value(), "a ui/.. hop must not reach the package root");
        // A bare directory URL falls back to the declared entry — inside the UI subtree.
        assertEquals(200, controller.asset(pluginId, requestForAsset(pluginId, ""))
                .getStatusCode().value(), "the entry fallback stays reachable");
    }

    /**
     * A navigation ticket must redeem against the DECODED handler path: the ticket is minted
     * from the manifest's entry string, so an entry containing a character that arrives
     * percent-encoded on the wire (here: a space) must still pass. Pre-fix, the raw
     * {@code getRequestURI()} ("...a%20b.html") never matched the minted "...a b.html" and the
     * navigation was 403'd.
     */
    @Test
    void uiTicketsRedeemAgainstTheDecodedPath(@TempDir Path pluginsRoot) throws Exception {
        String pluginId = "test.encodedplugin";
        Path dir = Files.createDirectories(pluginsRoot.resolve(pluginId));
        Files.writeString(dir.resolve("manifest.json"), """
            {"schemaVersion":2,"id":"%s","name":"A","description":"t","version":"1.0.0",
             "author":"t","icon":"t","category":"OTHER","ui":{"entry":"ui/a b.html"},
             "backend":{"callTimeoutSeconds":60},"permissions":[],"official":false,"aiTools":[]}
            """.formatted(pluginId));
        Files.createDirectories(dir.resolve("ui"));
        Files.writeString(dir.resolve("ui/a b.html"), "<!doctype html><title>ui</title>");
        PluginRuntimeController controller = new PluginRuntimeController(
                new PluginPackageService(pluginsRoot.toString()),
                mock(PluginProcessManager.class), mock(PluginLogStore.class),
                new fan.summer.fengyu.web.StreamTicketService());

        String ticket = controller.uiTicket(pluginId).getBody().ticket();
        var request = new org.springframework.mock.web.MockHttpServletRequest(
                "GET", "/plugin-runtime/" + pluginId + "/ui/a%20b.html");
        // The container hands the controller the DECODED path; the raw request URI still
        // carries %20 — exactly the production mismatch this test pins.
        request.setAttribute(org.springframework.web.servlet.HandlerMapping
                .PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, "/plugin-runtime/" + pluginId + "/ui/a b.html");
        request.setParameter("uiTicket", ticket);

        assertEquals(200, controller.asset(pluginId, request).getStatusCode().value(),
                "the ticket must redeem against the decoded path, not the raw percent-encoded URI");
    }

    /**
     * The live log stream keeps an idle client alive with comment frames (a quiet plugin
     * logs nothing for minutes), and a failed comment send routes into the connection's
     * unsubscribe — the same cleanup every terminal callback runs.
     */
    @Test
    void logStreamHeartbeatBeatsWhileIdleAndUnsubscribesWhenTheClientDies() {
        PluginRuntimeController controller = new PluginRuntimeController(
                mock(PluginPackageService.class), mock(PluginProcessManager.class),
                mock(PluginLogStore.class), new fan.summer.fengyu.web.StreamTicketService());
        java.util.List<String> comments = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean dead = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean unsubscribed =
                new java.util.concurrent.atomic.AtomicBoolean();
        // Mirrors a container-backed emitter: sends succeed until the connection breaks.
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
                new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(0L) {
                    @Override
                    public void send(SseEventBuilder builder) throws java.io.IOException {
                        if (dead.get()) throw new java.io.IOException("client closed");
                        for (org.springframework.web.servlet.mvc.method.annotation
                                .ResponseBodyEmitter.DataWithMediaType piece : builder.build()) {
                            if (piece.getData() instanceof String text && text.startsWith(":")) {
                                comments.add(text);
                            }
                        }
                    }
                };
        java.util.concurrent.atomic.AtomicReference<fan.summer.fengyu.web.SseHeartbeat> heartbeatRef =
                new java.util.concurrent.atomic.AtomicReference<>();

        fan.summer.fengyu.web.SseHeartbeat heartbeat = controller.logStreamHeartbeat(
                emitter, "test.plugin",
                () -> () -> {
                    unsubscribed.set(true);
                    fan.summer.fengyu.web.SseHeartbeat own = heartbeatRef.get();
                    if (own != null) own.stop();
                },
                java.time.Duration.ofMillis(50));
        heartbeatRef.set(heartbeat);
        heartbeat.start();

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(2))
                .until(() -> !comments.isEmpty());

        // The client dies: the next failed comment send must run the unsubscribe and stop
        // the heartbeat — no further beats, no lingering thread.
        dead.set(true);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(2))
                .until(unsubscribed::get);
        int atDeath = comments.size();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(2))
                .until(() -> !heartbeat.isAlive());
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertEquals(atDeath, comments.size(), "no further beats after the client died");
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
