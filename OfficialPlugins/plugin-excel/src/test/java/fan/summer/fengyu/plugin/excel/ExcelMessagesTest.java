package fan.summer.fengyu.plugin.excel;

import fan.summer.fengyu.sdk.PluginMessages;
import fan.summer.fengyu.sdk.WorkerLocale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that the Excel worker message bundles ship complete, matching key sets in both locales so
 * neither ever renders a raw key, and that the {@code analyze} summary / unknown-action message
 * resolve localized through {@link WorkerLocale}.
 */
class ExcelMessagesTest {

    private final PluginMessages msgs =
            PluginMessages.forClassLoader(PluginMessages.DEFAULT_BASE_NAME, ExcelPlugin.class);

    @AfterEach
    void clear() {
        WorkerLocale.clear();
    }

    @Test
    void enAndZhBundlesHaveIdenticalKeys() {
        ResourceBundle en = ResourceBundle.getBundle("i18n.messages", java.util.Locale.ENGLISH,
                ExcelPlugin.class.getClassLoader());
        ResourceBundle zh = ResourceBundle.getBundle("i18n.messages", java.util.Locale.SIMPLIFIED_CHINESE,
                ExcelPlugin.class.getClassLoader());
        assertEquals(en.keySet(), zh.keySet(), "en/zh excel bundles must keep identical key sets");
    }

    @Test
    void analyzedSummaryIsLocalized() {
        WorkerLocale.set("en");
        assertEquals("analyzed 3 sheet(s)", msgs.format("ex.analyzed", 3));
        WorkerLocale.set("zh");
        assertEquals("已分析 3 个工作表", msgs.format("ex.analyzed", 3));
    }

    @Test
    void unknownActionIsLocalized() {
        WorkerLocale.set("zh");
        assertTrue(msgs.format("ex.err.unknownAction", "frobnicate").contains("frobnicate"));
        assertEquals("未知操作：frobnicate", msgs.format("ex.err.unknownAction", "frobnicate"));
    }

    @Test
    void sheetNotFoundIsLocalizedWithTwoArgs() {
        WorkerLocale.set("en");
        assertEquals("Sheet not found: Data, available sheets: [Alpha, Beta]",
                msgs.format("ex.util.sheetNotFound", "Data", "[Alpha, Beta]"));
        WorkerLocale.set("zh");
        assertEquals("工作表未找到：Data，可用工作表：[Alpha, Beta]",
                msgs.format("ex.util.sheetNotFound", "Data", "[Alpha, Beta]"));
    }
}
