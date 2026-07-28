package fan.summer.fengyu.web.controller;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
