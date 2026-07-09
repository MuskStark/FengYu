package fan.summer.zhiflow.api.plugin;

import fan.summer.zhiflow.api.IconStyle;
import fan.summer.zhiflow.api.ToolCategory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PluginDescriptorTest {

    @Test
    void descriptorCarriesSupportsAiAndSource() {
        PluginDescriptor d = new PluginDescriptor(
            "fan.summer.markdown", "MD", "desc", ToolCategory.TEXT,
            "language-markdown", IconStyle.BLUE, "4.0.0",
            "/plugin-ui/markdown/index.js", true, PluginSource.OFFICIAL);
        assertTrue(d.supportsAi());
        assertEquals(PluginSource.OFFICIAL, d.source());
    }

    @Test
    void pluginSourceLabelKeys() {
        assertEquals("source.official", PluginSource.OFFICIAL.getLabelKey());
        assertEquals("source.third_party", PluginSource.THIRD_PARTY.getLabelKey());
    }
}
