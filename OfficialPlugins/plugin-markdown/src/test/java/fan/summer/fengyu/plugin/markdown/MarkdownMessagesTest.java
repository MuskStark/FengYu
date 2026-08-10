package fan.summer.fengyu.plugin.markdown;

import fan.summer.fengyu.sdk.PluginMessages;
import fan.summer.fengyu.sdk.WorkerLocale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that the markdown worker message bundles ship complete, matching key sets in both locales so
 * neither ever renders a raw key, and that the {@code render} summary / unknown-action message
 * resolve localized through {@link WorkerLocale}.
 */
class MarkdownMessagesTest {

    private final PluginMessages msgs =
            PluginMessages.forClassLoader(PluginMessages.DEFAULT_BASE_NAME, MarkdownPlugin.class);

    @AfterEach
    void clear() {
        WorkerLocale.clear();
    }

    @Test
    void enAndZhBundlesHaveIdenticalKeys() {
        ResourceBundle en = ResourceBundle.getBundle("i18n.messages", java.util.Locale.ENGLISH,
                MarkdownPlugin.class.getClassLoader());
        ResourceBundle zh = ResourceBundle.getBundle("i18n.messages", java.util.Locale.SIMPLIFIED_CHINESE,
                MarkdownPlugin.class.getClassLoader());
        assertEquals(en.keySet(), zh.keySet(), "en/zh markdown bundles must keep identical key sets");
    }

    @Test
    void renderedSummaryIsLocalized() {
        WorkerLocale.set("en");
        assertEquals("rendered 42 chars", msgs.format("md.rendered", 42));
        WorkerLocale.set("zh");
        assertEquals("已渲染 42 字符", msgs.format("md.rendered", 42));
    }

    @Test
    void unknownActionIsLocalized() {
        WorkerLocale.set("zh");
        assertTrue(msgs.format("md.unknownAction", "frobnicate").contains("frobnicate"));
        assertEquals("未知操作：frobnicate", msgs.format("md.unknownAction", "frobnicate"));
    }
}
