package fan.summer.fengyu.plugin.markdown;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownPluginSecurityTest {

    @Test
    void renderEscapesRawHtmlAndSanitizesActiveUrls() {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) new MarkdownPlugin().invoke(
                "render",
                Map.of("markdown",
                        "<img src=x onerror=\"parent.postMessage({source:'fengyu-plugin'}, '*')\">\n\n"
                        + "[click](javascript:alert(1))"));

        String html = (String) result.get("html");
        assertFalse(html.contains("<img"));
        assertFalse(html.contains("javascript:"));
        assertTrue(html.contains("&lt;img"));
    }
}
