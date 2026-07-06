package fan.summer.ai.util;

import fan.summer.zhiflow.api.theme.ThemeService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownRendererTest {
    @Test
    void darkRenderUsesDarkBackground() {
        String html = MarkdownRenderer.render("# hi", ThemeService.Theme.DARK);
        assertTrue(html.contains("#1e1e2e"), "dark html should embed dark bg");
    }

    @Test
    void lightRenderUsesLightBackground() {
        String html = MarkdownRenderer.render("# hi", ThemeService.Theme.LIGHT);
        assertTrue(html.contains("#ffffff"), "light html should embed light bg");
    }
}
