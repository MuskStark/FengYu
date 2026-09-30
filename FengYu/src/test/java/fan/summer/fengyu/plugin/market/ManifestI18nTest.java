package fan.summer.fengyu.plugin.market;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Isolated tests for {@link ManifestI18n} locale resolution and family fallback. These bypass the
 * Spring LocaleContextHolder (which {@link ManifestI18n#currentLocale()} wraps) by calling the
 * explicit-locale overloads, so they pin the lookup logic independently of the running context.
 */
class ManifestI18nTest {

    private static final String DEFAULT_NAME = "Excel Splitter";
    private static final String DEFAULT_DESC = "Split Excel workbooks by sheet, column value, or complex rules";

    private static PluginManifest manifest(Map<String, PluginManifest.LocaleOverride> i18n) {
        List<PluginManifest.AiTool> tools = List.of(
                new PluginManifest.AiTool("excel_analyze",
                        "Analyze the granted Excel workbook before configuring or executing any split; returns sheet names.",
                        "excel_analyze", 30L, "read"),
                new PluginManifest.AiTool("excel_cancel", "Clear the active Excel split session.",
                        "excel_cancel", 30L, "read"));
        return new PluginManifest(2, "fan.summer.excel", DEFAULT_NAME, DEFAULT_DESC, "4.0.0-alpha.8",
                "FengYu", "file-excel", "file",
                new PluginManifest.Ui("ui/index.html"),
                new PluginManifest.Backend(60L),
                List.of("files.read"), "https://example.com", true, null, tools, i18n, null);
    }

    @Test
    void shortZhLocaleResolvesOverride() {
        var i18n = Map.of("zh", new PluginManifest.LocaleOverride(
                "Excel 拆分器", "按工作表拆分", null));
        PluginManifest m = manifest(i18n);
        assertEquals("Excel 拆分器", ManifestI18n.name(m, "zh"));
        assertEquals("按工作表拆分", ManifestI18n.description(m, "zh"));
    }

    @Test
    void zhCnFallsBackToZhFamily() {
        // The manifest ships only a "zh" block; a "zh-CN" request must fall back to it.
        var i18n = Map.of("zh", new PluginManifest.LocaleOverride(
                "Excel 拆分器", "按工作表拆分", null));
        PluginManifest m = manifest(i18n);
        assertEquals("Excel 拆分器", ManifestI18n.name(m, "zh-cn"));
        assertEquals("Excel 拆分器", ManifestI18n.name(m, "zh-CN"));
    }

    @Test
    void exactZhCnPreferredOverZhFamily() {
        var i18n = Map.of(
                "zh", new PluginManifest.LocaleOverride("Excel 拆分器", null, null),
                "zh-CN", new PluginManifest.LocaleOverride("Excel 拆分器（简体）", null, null));
        PluginManifest m = manifest(i18n);
        assertEquals("Excel 拆分器（简体）", ManifestI18n.name(m, "zh-CN"));
        // A plain "zh" request should still hit the family block, not the zh-CN one.
        assertEquals("Excel 拆分器", ManifestI18n.name(m, "zh"));
    }

    @Test
    void missingLocaleFallsBackToDefault() {
        // No i18n block at all — every locale returns the top-level English defaults.
        PluginManifest m = manifest(null);
        assertEquals(DEFAULT_NAME, ManifestI18n.name(m, "zh"));
        assertEquals(DEFAULT_NAME, ManifestI18n.name(m, "zh-CN"));
        assertEquals(DEFAULT_NAME, ManifestI18n.name(m, "en"));
    }

    @Test
    void partialOverrideFallsBackPerField() {
        // Only name is translated; description keeps the English default.
        var i18n = Map.of("zh", new PluginManifest.LocaleOverride("Excel 拆分器", null, null));
        PluginManifest m = manifest(i18n);
        assertEquals("Excel 拆分器", ManifestI18n.name(m, "zh"));
        assertEquals(DEFAULT_DESC, ManifestI18n.description(m, "zh"));
    }

    @Test
    void aiToolDescriptionLocalizedByToolName() {
        var toolOverride = Map.of("excel_analyze",
                new PluginManifest.AiToolOverride(null, "分析工作簿结构"));
        var i18n = Map.of("zh", new PluginManifest.LocaleOverride(null, null, toolOverride));
        PluginManifest m = manifest(i18n);
        assertEquals("分析工作簿结构",
                ManifestI18n.aiToolDescription(m, "excel_analyze", "zh"));
        // A tool without an override falls back to its own top-level English description.
        assertEquals("Clear the active Excel split session.",
                ManifestI18n.aiToolDescription(m, "excel_cancel", "zh"));
    }

    @Test
    void flowNodeLocalizedByToolNameWithPerToolFallback() throws Exception {
        var json = JsonMapper.builder().build();
        var canonical = json.readTree("[{\"tool\":\"excel_analyze\",\"label\":\"Analyze\"}]");
        var localized = json.readTree("{\"excel_analyze\":{\"label\":\"分析\"}}");
        PluginManifest base = manifest(null);
        PluginManifest withFlow = new PluginManifest(base.schemaVersion(), base.id(), base.name(),
                base.description(), base.version(), base.author(), base.icon(), base.category(),
                base.ui(), base.backend(), base.permissions(), base.homepage(), base.official(),
                base.rpc(), base.aiTools(),
                Map.of("zh", new PluginManifest.LocaleOverride(null, null, null, localized)),
                canonical, base.engines());

        assertEquals("分析", ManifestI18n.flowNode(withFlow, "excel_analyze", "zh").path("label").asText());
        assertEquals("Analyze", ManifestI18n.flowNode(withFlow, "excel_analyze", "en").path("label").asText());
        assertNull(ManifestI18n.flowNode(withFlow, "excel_cancel", "zh"));
    }

    @Test
    void nullManifestReturnsNull() {
        assertNull(ManifestI18n.name(null, "zh"));
        assertNull(ManifestI18n.description(null, "zh"));
        assertNull(ManifestI18n.aiToolDescription(null, "excel_analyze", "zh"));
        assertNull(ManifestI18n.flowNode(null, "excel_analyze", "zh"));
    }
}
