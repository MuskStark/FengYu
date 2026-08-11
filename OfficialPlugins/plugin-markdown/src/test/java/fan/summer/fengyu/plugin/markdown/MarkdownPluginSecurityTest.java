package fan.summer.fengyu.plugin.markdown;

import fan.summer.fengyu.sdk.PluginMessages;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the commonmark render's HTML-escaping and active-URL sanitization. The output the worker
 * places into {@code RenderOutput.html} (and the UI injects via {@code v-html}) must never carry an
 * unescaped raw tag or a {@code javascript:} link.
 */
class MarkdownPluginSecurityTest {

    @Test
    void renderHtmlEscapesRawHtmlAndSanitizesActiveUrls() {
        MarkdownPlugin plugin = new MarkdownPlugin(PluginMessages.forClassLoader(
                PluginMessages.DEFAULT_BASE_NAME, MarkdownPlugin.class));

        String html = plugin.renderHtml("<img src=x onerror=\"parent.postMessage({source:'fengyu-plugin'}, '*')\">\n\n"
                + "[click](javascript:alert(1))");

        assertFalse(html.contains("<img"));
        assertFalse(html.contains("javascript:"));
        assertTrue(html.contains("&lt;img"));
    }
}
