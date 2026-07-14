package fan.summer.fengyu.web.controller;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PluginRuntimeControllerTest {

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
